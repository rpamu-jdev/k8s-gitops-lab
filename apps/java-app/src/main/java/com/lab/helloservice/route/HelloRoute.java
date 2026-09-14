package com.lab.helloservice.route;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * REST routes for the sample app:
 *   GET {base-path}/hello   -> a friendly greeting
 *   GET {base-path}/version -> app + runtime version info
 *
 * {base-path} defaults to /sample/api, matching this app's Ingress path
 * prefix in the cluster - the app owns its full external path rather than
 * having a prefix stripped at the ingress layer.
 */
@Component
public class HelloRoute extends RouteBuilder {

    @Value("${app.version}")
    private String appVersion;

    @Value("${app.name}")
    private String appName;

    @Value("${app.greeting-prefix}")
    private String greetingPrefix;

    @Value("${app.environment}")
    private String environment;

    @Value("${app.base-path}")
    private String basePath;

    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                .bindingMode(RestBindingMode.json)
                .dataFormatProperty("prettyPrint", "true");

        rest(basePath)
                .get("/hello")
                    .to("direct:hello")
                .get("/version")
                    .to("direct:version");

        from("direct:hello")
                .routeId("hello-route")
                .setBody(this::helloBody);

        from("direct:version")
                .routeId("version-route")
                .setBody(this::versionBody);
    }

    private Map<String, String> helloBody(org.apache.camel.Exchange exchange) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", greetingPrefix + " from " + appName + " (" + environment + ")!");
        return body;
    }

    private Map<String, String> versionBody(org.apache.camel.Exchange exchange) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("name", appName);
        body.put("version", appVersion);
        body.put("environment", environment);
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("camelVersion", exchange.getContext().getVersion());
        return body;
    }
}
