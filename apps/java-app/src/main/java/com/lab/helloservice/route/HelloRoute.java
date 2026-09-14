package com.lab.helloservice.route;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * REST routes for the sample app:
 *   GET /api/hello   -> a friendly greeting
 *   GET /api/version -> app + runtime version info
 */
@Component
public class HelloRoute extends RouteBuilder {

    @Value("${app.version}")
    private String appVersion;

    @Value("${app.name}")
    private String appName;

    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                .bindingMode(RestBindingMode.json)
                .dataFormatProperty("prettyPrint", "true");

        rest("/api")
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
        body.put("message", "Hello from " + appName + "!");
        return body;
    }

    private Map<String, String> versionBody(org.apache.camel.Exchange exchange) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("name", appName);
        body.put("version", appVersion);
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("camelVersion", exchange.getContext().getVersion());
        return body;
    }
}
