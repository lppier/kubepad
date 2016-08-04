package de.qaware.cloud.nativ.kpad.marathon

import de.qaware.cloud.nativ.kpad.Cluster
import de.qaware.cloud.nativ.kpad.ClusterAppReplica
import de.qaware.cloud.nativ.kpad.ClusterDeploymentEvent
import de.qaware.cloud.nativ.kpad.ClusterNode
import org.apache.deltaspike.core.api.exclude.Exclude
import org.slf4j.Logger
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.enterprise.context.ApplicationScoped
import javax.enterprise.event.Event
import javax.inject.Inject
import javax.inject.Named

@Exclude(onExpression="cluster.service!=marathon")
@ApplicationScoped
open class MarathonCluster @Inject constructor(private val client : MarathonClient,
                                               @Named("scheduled")
                                               private val scheduler: ScheduledExecutorService,
                                               private val events: Event<ClusterDeploymentEvent>,
                                               private val logger: Logger) : Cluster {

    private val apps = mutableListOf<MarathonClient.App?>()
    private val deployments = mutableListOf<MarathonClient.Deployment>()

    @PostConstruct
    open fun init() {
        update()
        apps.forEach { logger.debug("Found Marathon app {}", it?.id) }
        watch()
    }

    private fun update() {
        val newApps = client.listApps().execute().body().apps.toMutableList()

        apps.forEachIndexed { i, app ->
            if(app == null) return
            val newApp = if (newApps[i].id.equals(app.id)) newApps[i] else newApps.find { it.id.equals(app.id) }

            if(newApp != null) { // app found in newApps
                newApps.remove(newApp)
                apps[i] = newApp
                if (app.instances < newApp.instances) { // -> scaled up
                    logger.debug("Scaled up app {} from {} to {} replicas.", app.id, app.instances, newApp.instances)
                    events.fire(ClusterDeploymentEvent(i, newApp.instances, labels(i),
                            ClusterDeploymentEvent.Type.SCALED_UP))
                } else if (app.instances > newApp.instances) { // -> scaled down
                    logger.debug("Scaled down app {} from {} to {} replicas.", app.id, app.instances, newApp.instances)
                    events.fire(ClusterDeploymentEvent(i, newApp.instances, labels(i),
                            ClusterDeploymentEvent.Type.SCALED_DOWN))
                }
            } else { // app not found in newApps -> deleted
                logger.debug("Deleted deployment {}.", apps[i]!!.id)
                apps[i] = null;
                events.fire(ClusterDeploymentEvent(i, 0, labels(i), ClusterDeploymentEvent.Type.DELETED))
            }
        }

        newApps.forEachIndexed { i, newApp -> // newApp not (yet) in apps -> added
            var index = apps.indexOfFirst { it == null }
            if (index == -1) {
                index = apps.count()
                apps.add(newApp)
            } else {
                apps[index] = newApp
            }
            events.fire(ClusterDeploymentEvent(index, newApp.instances, labels(index),
                    ClusterDeploymentEvent.Type.ADDED))
        }
    }

    override fun appCount(): Int = apps.count()

    override fun appExists(appIndex: Int): Boolean = appIndex < apps.count()

    override fun replicas(appIndex: Int): List<ClusterAppReplica> {
        val app = apps[appIndex]!!
        val instances = mutableListOf<ClusterAppReplica>();
        for(i in 0.until(app.instances)) {
            instances.add(object : ClusterAppReplica {
                override fun phase() : ClusterNode.Phase = ClusterNode.Phase.Running
                override fun name() : String = app.id + "-instance" + i;
            })
        }
        return instances
    }

    override fun scale(appIndex: Int, replicas: Int) {
        val app = apps[appIndex]
        if(app == null) {
            logger.error("Scaling failed! Not app at index {}.", appIndex)
            return
        }

        logger.debug("Scaling app {} to {} replicas.", app.id, replicas)
        val result = client.updateApp(app.id, MarathonClient.ScalingUpdate(replicas)).execute().body()
        val deployment = client.listDeployments().execute().body().find { it.id.equals(result.deploymentId) }!!
        logger.debug("Scaling successful. Created depolyment {}.", deployment.id)
        deployments.add(deployment)
    }

    private fun labels(appIndex: Int) : MutableMap<String, String> {
        return mutableMapOf<String, String>()
    }

    override fun clear() {
        apps.clear()
        deployments.clear()
    }

    private fun watch() {
        scheduler.scheduleWithFixedDelay({
            update()
        }, 2500, 2500, TimeUnit.MILLISECONDS)
    }
}