package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.TokenBucketRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestTimeout = Duration.ofSeconds(60)

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(60))
        .build()

    init {
        System.setProperty("jdk.httpclient.connectionPoolSize", "10000")
        System.setProperty("jdk.httpclient.maxConnections", "10000")
    }

    private val rateLimiter = TokenBucketRateLimiter(
        rate = 1000,
        bucketMaxCapacity = 1000,
        window = 1,
        timeUnit = TimeUnit.SECONDS,
    )

    private val dispatcher = Dispatchers.IO
    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        coroutineScope.launch {
            try {
                processPayment(paymentId, amount, transactionId, deadline)
            } catch (e: Exception) {
                logger.error("[$accountName] Error processing payment $paymentId: ${e.message}", e)
            }
        }
    }

    private suspend fun processPayment(paymentId: UUID, amount: Int, transactionId: UUID, deadline: Long) {
//        rateLimiter.tickSuspended()

        try {
            var response = makeCall(paymentId, amount, transactionId)

            if (response.statusCode() in 200..299) {
                logger.info("[$accountName] Payment successful for txId: $transactionId, payment: $paymentId")
            } else {
                logger.warn("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId, status: ${response.statusCode()}")
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("[$accountName] Request timed out due to approaching deadline for payment $paymentId")
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    private suspend fun makeCall(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID
    ) = suspendCoroutine<HttpResponse<String>> { continuation ->
        val url = "http://localhost:1234/external/process?" +
            "serviceName=${serviceName}&" +
            "accountName=${accountName}&" +
            "transactionId=$transactionId&" +
            "paymentId=$paymentId&" +
            "amount=$amount"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(requestTimeout)
            .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    logger.debug("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, code: ${response.statusCode()}")
                    continuation.resume(response)
                }
            }
    }
}
