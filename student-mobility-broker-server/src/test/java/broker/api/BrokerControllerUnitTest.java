package broker.api;

import broker.InMemoryServiceRegistry;
import broker.WireMockExtension;
import broker.domain.BrokerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static broker.api.BrokerController.BROKER_REQUEST_SESSION_KEY;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BrokerControllerUnitTest {

    private BrokerController brokerController = new BrokerController(
            "http://localhost",
            "http://localhost",
            false,
            false,
            new InMemoryServiceRegistry(new ClassPathResource("service-registry-test.yml")));

    @RegisterExtension
    WireMockExtension mockServer = new WireMockExtension(8081);

    @Test
    public void playground() {
        stubFor(post(urlPathMatching("/api/start")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"result\":\"ok\", \"code\":\"ok\"}")));

        Map<String, String> body = new HashMap<>();
        body.put("code", "200");
        body.put("result", "playground");

        HttpServletRequest request = new MockHttpServletRequest();
        BrokerRequest brokerRequest = new BrokerRequest();
        brokerRequest.setHomeInstitutionSchacHome("utrecht.nl");

        request.getSession(true).setAttribute(BROKER_REQUEST_SESSION_KEY, brokerRequest);
        Map<String, Object> res = brokerController.start(request, body);
        assertEquals("ok",res.get("result"));

    }
}
