package no.nav.pam.feed.ad

import io.ktor.application.call
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.principal
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.content.TextContent
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.request.host
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.util.filter
import io.ktor.util.flattenForEach
import mu.KotlinLogging
import mu.withLoggingContext
import java.io.IOException

internal const val MAX_TOTAL_HITS = 5000

private val log = KotlinLogging.logger { }

fun Route.feed(searchApiHost: String, httpClient: HttpClient) {
    val url = "$searchApiHost/public-feed/ad/_search"
    log.info("Using search API host: ${searchApiHost}")

    get("/api/v1/ads") {
        val subject = call.principal<JWTPrincipal>()?.payload?.subject ?: "?"
        withLoggingContext("U" to subject) {
            log.debug { "Auth subject: ${subject}" }
            val elasticRequestAsJson = call.parameters.toElasticRequest().asJson()

            val response = httpClient.post<SearchResponseRoot>(url) {
                body = TextContent(elasticRequestAsJson, ContentType.Application.Json)
            }

            call.respond(mapResult(response, call.parameters.page, call.parameters.size, call.request.host()))
        }
    }
}

fun StatusPages.Configuration.feed() {

    exception<IOException> {
        log.error(it) { "Failed to communicate with backend" }
        call.respond(HttpStatusCode.BadGateway, "Failed to communicate with backend")
    }
    exception<NumberFormatException>  {
        log.error { "Bad numeric parameter value: ${it.message}" }
        call.respond(HttpStatusCode.BadRequest, "Bad numeric parameter value: ${it.message}")
    }
}

private fun Parameters.toElasticRequest() = ElasticRequest(this.size, this.page, this.valueFilters(),
        this.locationValueFilters(), this.rangeFilters())

private val Parameters.size get() = (this["size"]?.toInt() ?: 20).coerceIn(1 .. 100 )
private val Parameters.page get() = (this["page"]?.toInt() ?: 0).coerceIn(0 .. MAX_TOTAL_HITS / this.size)
private val validRangeFilters = listOf("updated", "published")
private val validValueFilters = listOf("uuid", "source", "orgnr")
private val validLocationValueFilters = listOf("municipal", "county")

private fun Parameters.valueFilters() = mutableListOf<ValueParam>()
        .apply { this@valueFilters.filter { key, _ -> key in validValueFilters }
                .flattenForEach { key, value -> add(value.parseAsValueFilter(key, true).get()) } }

private fun Parameters.locationValueFilters() = mutableListOf<ValueParam>()
        .apply { this@locationValueFilters.filter { key, _ -> key in validLocationValueFilters }
                .flattenForEach { key, value -> add(value.parseAsLocationValueFilter(key, true).get()) } }

private fun Parameters.rangeFilters() = mutableListOf<DateParam>()
        .apply { this@rangeFilters.filter { key, _ -> key in validRangeFilters }
                .flattenForEach { key, value -> add(value.parseAsDateFilter(key, true).get()) } }