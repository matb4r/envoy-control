package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.api.v2.core.DataSource
import io.envoyproxy.envoy.api.v2.route.DirectResponseAction
import io.envoyproxy.envoy.api.v2.route.HeaderMatcher
import io.envoyproxy.envoy.api.v2.route.RedirectAction
import io.envoyproxy.envoy.api.v2.route.Route
import io.envoyproxy.envoy.api.v2.route.RouteAction
import io.envoyproxy.envoy.api.v2.route.RouteMatch

internal class AdminRoutesFactory(
    private val properties: RoutesProperties
) {
    private val adminRouteAction = RouteAction.newBuilder()
        .setCluster("this_admin")
        .setPrefixRewrite("/")

    private val adminRedirectRoute = Route.newBuilder()
        .setMatch(
            RouteMatch.newBuilder()
                .setPrefix(properties.admin.pathPrefix)
                .addHeaders(httpMethodMatcher(HttpMethod.GET))
        )
        .setRedirect(RedirectAction.newBuilder()
            .setPathRedirect(properties.admin.pathPrefix + "/"))
        .build()

    private val adminRoute = Route.newBuilder()
        .setMatch(
            RouteMatch.newBuilder()
                .setPrefix(properties.admin.pathPrefix + "/")
        )
        .setRoute(adminRouteAction)
        .build()

    private val adminPostRoute = createAuthorizedRoute(
        properties.admin.pathPrefix + "/",
        "this_admin",
        "/",
        HttpMethod.POST
    )

    private fun generateSecuredAdminRoutes(): List<Route> {
        return properties.admin.securedPaths
            .flatMap {
                val authorizedPath = createAuthorizedRoute(
                    properties.admin.pathPrefix + it.pathPrefix,
                    "this_admin",
                    it.pathPrefix,
                    HttpMethod.valueOf(it.method)
                )
                listOf(authorizedPath.authorized, authorizedPath.unauthorized)
            }
    }

    fun generateAdminRoutes(): List<Route> {
        return guardAccessWithDisableHeader() +
                generateSecuredAdminRoutes() +
                listOfNotNull(
                        adminPostRoute.authorized.takeIf { properties.admin.publicAccessEnabled },
                        adminPostRoute.unauthorized.takeIf { properties.admin.publicAccessEnabled },
                        adminRoute.takeIf { properties.admin.publicAccessEnabled },
                        adminRedirectRoute.takeIf { properties.admin.publicAccessEnabled }
                )
    }

    private fun createAuthorizedRoute(
        pathPrefix: String,
        cluster: String,
        prefixRewrite: String,
        httpMethod: HttpMethod
    ): AuthorizationRoute {
        val routeAction = RouteAction.newBuilder()
            .setCluster(cluster)
            .setPrefixRewrite(prefixRewrite)

        val authorizedRoute: Route = Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix(pathPrefix)
                    .addHeaders(exactHeader("authorization", properties.admin.token))
                    .addHeaders(httpMethodMatcher(httpMethod))
            )
            .setRoute(routeAction)
            .build()

        val unauthorizedRoute: Route = Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix(pathPrefix)
                    .addHeaders(httpMethodMatcher(httpMethod))
            )
            .setDirectResponse(DirectResponseAction.newBuilder()
                .setStatus(properties.authorization.unauthorizedStatusCode)
                .setBody(DataSource.newBuilder()
                    .setInlineString(properties.authorization.unauthorizedResponseMessage)
                )
            )
            .build()
        return AuthorizationRoute(authorizedRoute, unauthorizedRoute)
    }

    fun guardAccessWithDisableHeader(): List<Route> {
        if (properties.admin.disable.onHeader.isEmpty()) {
            return emptyList()
        }

        val routeDenyingRequestsWithDisableHeaderOnPath =
                { matchCustomizer: (RouteMatch.Builder) -> RouteMatch.Builder ->
                    Route.newBuilder()
                            .setMatch(
                                    matchCustomizer.invoke(RouteMatch.newBuilder())
                                            .addHeaders(HeaderMatcher.newBuilder()
                                                    .setName(properties.admin.disable.onHeader)
                                                    .build()
                                            )
                            )
                            .setDirectResponse(
                                    DirectResponseAction.newBuilder()
                                            .setStatus(properties.admin.disable.responseCode)
                                            .build()
                            )
                            .build()
                }

        return listOf(
                routeDenyingRequestsWithDisableHeaderOnPath { b ->
                    b.setPath(properties.admin.pathPrefix)
                },
                routeDenyingRequestsWithDisableHeaderOnPath { b ->
                    b.setPrefix(properties.admin.pathPrefix + "/")
                }
        )
    }
}
