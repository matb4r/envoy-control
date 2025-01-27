package pl.allegro.tech.servicemesh.envoycontrol.config

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.Headers
import okhttp3.RequestBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.awaitility.Awaitility
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.actuate.health.Status
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed class EnvoyConfigFile(val filePath: String)
object AdsAllDependencies : EnvoyConfigFile("envoy/config_ads_all_dependencies.yaml")
object Ads : EnvoyConfigFile("envoy/config_ads.yaml")
object Xds : EnvoyConfigFile("envoy/config_xds.yaml")
object RandomConfigFile :
    EnvoyConfigFile(filePath = if (Random.nextBoolean()) Ads.filePath else Xds.filePath)

abstract class EnvoyControlTestConfiguration : BaseEnvoyTest() {
    companion object {
        private val client = OkHttpClient.Builder()
            // envoys default timeout is 15 seconds while OkHttp is 10
            .readTimeout(Duration.ofSeconds(20))
            .build()

        lateinit var envoyContainer1: EnvoyContainer
        lateinit var envoyContainer2: EnvoyContainer
        lateinit var localServiceContainer: EchoContainer
        lateinit var envoyControl1: EnvoyControlTestApp
        lateinit var envoyControl2: EnvoyControlTestApp
        var envoyControls: Int = 1
        var envoys: Int = 1

        @JvmStatic
        fun setup(
            envoyConfig: EnvoyConfigFile = RandomConfigFile,
            appFactoryForEc1: (Int) -> EnvoyControlTestApp = defaultAppFactory(),
            appFactoryForEc2: (Int) -> EnvoyControlTestApp = appFactoryForEc1,
            envoyControls: Int = 1,
            envoys: Int = 1,
            envoyConnectGrpcPort: Int? = null,
            envoyConnectGrpcPort2: Int? = null,
            ec1RegisterPort: Int? = null,
            ec2RegisterPort: Int? = null,
            instancesInSameDc: Boolean = false
        ) {
            assertThat(envoyControls == 1 || envoyControls == 2).isTrue()
            assertThat(envoys == 1 || envoys == 2).isTrue()

            localServiceContainer = EchoContainer().also { it.start() }

            Companion.envoyControls = envoyControls

            envoyControl1 = appFactoryForEc1(consulHttpPort).also { it.run() }

            if (envoyControls == 2) {
                envoyControl2 = appFactoryForEc2(consul2HttpPort).also { it.run() }
            }

            envoyContainer1 = createEnvoyContainer(
                instancesInSameDc,
                envoyConfig,
                envoyConnectGrpcPort,
                envoyConnectGrpcPort2
            )

            waitForEnvoyControlsHealthy()
            registerEnvoyControls(ec1RegisterPort, ec2RegisterPort, instancesInSameDc)
            envoyContainer1.start()

            if (envoys == 2) {
                envoyContainer2 = createEnvoyContainer(
                    true,
                    envoyConfig,
                    envoyConnectGrpcPort,
                    envoyConnectGrpcPort2,
                    echoContainer.ipAddress()
                )
                envoyContainer2.start()
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            envoyContainer1.stop()
            envoyControl1.stop()
            if (envoyControls == 2) {
                envoyControl2.stop()
            }
        }

        private fun createEnvoyContainer(
            instancesInSameDc: Boolean,
            envoyConfig: EnvoyConfigFile,
            envoyConnectGrpcPort: Int?,
            envoyConnectGrpcPort2: Int?,
            localServiceIp: String = localServiceContainer.ipAddress()
        ): EnvoyContainer {
            return if (envoyControls == 2 && instancesInSameDc) {
                EnvoyContainer(
                    envoyConfig.filePath,
                    localServiceIp,
                    envoyConnectGrpcPort ?: envoyControl1.grpcPort,
                    envoyConnectGrpcPort2 ?: envoyControl2.grpcPort
                ).withNetwork(network)
            } else {
                EnvoyContainer(
                    envoyConfig.filePath,
                    localServiceIp,
                    envoyConnectGrpcPort ?: envoyControl1.grpcPort
                ).withNetwork(network)
            }
        }

        fun registerEnvoyControls(
            ec1RegisterPort: Int?,
            ec2RegisterPort: Int?,
            instancesInSameDc: Boolean
        ) {
            registerService(
                "1",
                envoyControl1.appName,
                "localhost",
                ec1RegisterPort ?: envoyControl1.appPort,
                consulOperationsInFirstDc
            )
            if (envoyControls == 2) {
                registerService(
                    "2",
                    envoyControl2.appName,
                    "localhost",
                    ec2RegisterPort ?: envoyControl2.appPort,
                    if (instancesInSameDc) consulOperationsInFirstDc else consulOperationsInSecondDc
                )
            }
        }

        private fun waitForEnvoyControlsHealthy() {
            await().atMost(30, TimeUnit.SECONDS).untilAsserted {
                assertThat(envoyControl1.isHealthy()).isTrue()
                if (envoyControls == 2) {
                    assertThat(envoyControl2.isHealthy()).isTrue()
                }
            }
        }

        fun callEcho(url: String = envoyContainer1.egressListenerUrl()): Response =
            call("echo", url)

        fun callDomain(domain: String, url: String = envoyContainer1.egressListenerUrl()): Response =
            call(domain, url)

        fun callService(
            service: String,
            url: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf()
        ): Response = call(service, url, headers)

        private fun call(
            host: String,
            url: String = envoyContainer1.egressListenerUrl(),
            headers: Map<String, String> = mapOf()
        ): Response =
            client.newCall(
                Request.Builder()
                    .get()
                    .header("Host", host)
                    .apply {
                        headers.forEach { name, value -> header(name, value) }
                    }
                    .url(url)
                    .build()
            )
                .execute()

        fun callServiceWithOriginalDst(originalDstUrl: String, envoyUrl: String): Response =
            client.newCall(
                Request.Builder()
                    .get()
                    .header("Host", "envoy-original-destination")
                    .header("x-envoy-original-dst-host", originalDstUrl)
                    .url(envoyUrl)
                    .build()
            )
                .execute()

        fun callLocalService(endpoint: String, headers: Headers): Response =
            client.newCall(
                    Request.Builder()
                        .get()
                        .headers(headers)
                        .url(envoyContainer1.ingressListenerUrl() + endpoint)
                        .build()
                )
                .execute()

        fun callPostLocalService(endpoint: String, headers: Headers, body: RequestBody, envoyContainer: EnvoyContainer = envoyContainer1): Response =
            client.newCall(
                    Request.Builder()
                        .post(body)
                        .headers(headers)
                        .url(envoyContainer.ingressListenerUrl() + endpoint)
                        .build()
                )
                .execute()

        private fun waitForConsulSync() {
            await().atMost(defaultDuration).until { !callEcho().use { it.isSuccessful } }
        }

        private fun defaultAppFactory(): (Int) -> EnvoyControlRunnerTestApp {
            return { consulPort ->
                EnvoyControlRunnerTestApp(
                    consulPort = consulPort
                )
            }
        }
    }

    fun waitUntilEchoCalledThroughEnvoyResponds(target: EchoContainer) {
        untilAsserted {
            // when
            val response = callEcho()

            // then
            assertThat(response).isOk().isFrom(target)
        }
    }

    /**
     * We have to retrieve the bean manually instead of @Autowired because the app is created in manual way
     * instead of using the JUnit Spring Extension
     */
    inline fun <reified T> bean(): T = envoyControl1.bean(T::class.java)

    fun untilAsserted(wait: org.awaitility.Duration = defaultDuration, fn: () -> (Unit)) {
        Awaitility.await().atMost(wait).untilAsserted(fn)
    }

    fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
        matches { it.isSuccessful }
        return this
    }

    fun ObjectAssert<Response>.isFrom(echoContainer: EchoContainer): ObjectAssert<Response> {
        matches {
            it.body()?.use { it.string().contains(echoContainer.response) } ?: false
        }
        return this
    }

    fun ObjectAssert<Response>.isUnreachable(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 503 || it.code() == 504
        }, "is unreachable")
        return this
    }

    fun ObjectAssert<Response>.isForbidden(): ObjectAssert<Response> {
        matches({
            it.body()?.close()
            it.code() == 403
        }, "is forbidden")
        return this
    }

    fun ObjectAssert<Response>.hasLocationHeaderFrom(serviceName: String): ObjectAssert<Response> {
        matches { it.headers("location").contains("http://$serviceName/") }
        return this
    }

    fun ObjectAssert<Health>.isStatusHealthy(): ObjectAssert<Health> {
        matches { it.status == Status.UP }
        return this
    }

    fun ObjectAssert<Health>.hasEnvoyControlCheckPassed(): ObjectAssert<Health> {
        matches { it.details.get("envoyControl")?.status == Status.UP }
        return this
    }

    fun ObjectAssert<ServicesState>.hasServiceStateChanged(): ObjectAssert<ServicesState> {
        matches { it.serviceNames().isNotEmpty() }
        return this
    }

    @AfterEach
    fun cleanupTest() {
        deregisterAllServices()
        envoyContainer1.admin().resetCounters()
        if (envoys == 2) {
            envoyContainer2.admin().resetCounters()
        }
        waitForConsulSync()
    }

}
