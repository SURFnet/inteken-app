package broker.api;

import broker.ServiceRegistry;
import broker.domain.BrokerRequest;
import broker.domain.CourseAuthentication;
import broker.domain.EnrollmentRequest;
import broker.domain.Institution;
import broker.exception.NotFoundException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

@RestController
public class BrokerController {

    public static final String BROKER_REQUEST_SESSION_KEY = "BROKER_REQUEST_SESSION_KEY";
    public static final String OFFERING_SESSION_KEY = "OFFERING_SESSION_KEY";

    private static final Log LOG = LogFactory.getLog(BrokerController.class);

    private final String clientUrl;

    private final ServiceRegistry serviceRegistry;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, Object> featureToggles = new HashMap<>();
    private final URI tokenEndpoint;
    private final String clientId;
    private final String secret;

    private final ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<Map<String, Object>>() {
    };

    public BrokerController(@Value("${config.broker_client_url}") String clientUrl,
                            @Value("${config.start_broker_endpoint}") String startBrokerEndpoint,
                            @Value("${config.oauth2.token_endpoint}") URI tokenEndpoint,
                            @Value("${config.oauth2.client_id}") String clientId,
                            @Value("${config.oauth2.secret}") String secret,
                            @Value("${config.local}") boolean local,
                            @Value("${config.allow_playground}") boolean allowPlayground,
                            ServiceRegistry serviceRegistry) {
        this.clientUrl = clientUrl;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.secret = secret;
        this.serviceRegistry = serviceRegistry;
        this.featureToggles.put("startBrokerEndpoint", startBrokerEndpoint);
        this.featureToggles.put("local", local);
        this.featureToggles.put("allowPlayground", allowPlayground);
    }

    /*
     * Endpoint called by the catalog form submit. Give browser-control back to the GUI
     */
    @PostMapping(value = "/api/broker", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public View brokerRequest(HttpServletRequest request, @ModelAttribute BrokerRequest brokerRequest) throws UnsupportedEncodingException {
        LOG.debug("Starting session for brokerRequest: " + brokerRequest);
        try {
            //we want to fail fast
            brokerRequest.validate();
            this.getInstitution(brokerRequest.getHomeInstitutionSchacHome());
            this.getInstitution(brokerRequest.getGuestInstitutionSchacHome());
        } catch (IllegalArgumentException e) {
            return new RedirectView(clientUrl + "?error=invalid_request");
        } catch (NotFoundException e) {
            return new RedirectView(clientUrl + "?error=" + URLEncoder.encode(e.getMessage(), "UTF-8"));
        }
        //This establishes a session ID for the client
        request.getSession().setAttribute(BROKER_REQUEST_SESSION_KEY, brokerRequest);
        return new RedirectView(clientUrl + "?step=approve");
    }

    /*
     * Endpoint called by the GUI to get the feature toggles
     */
    @GetMapping(value = "/api/features")
    public Map<String, Object> features() {
    	LOG.debug("Received request for feature toggles.");
        return featureToggles;
    }


    /*
     * Endpoint called by the GUI in first rendering
     */
    @GetMapping(value = "/api/offering")
    public Map<String, Object> offering(HttpServletRequest request) {
        BrokerRequest brokerRequest = (BrokerRequest) request.getSession().getAttribute(BROKER_REQUEST_SESSION_KEY);

        LOG.debug("Received request for offering for brokerRequest: " + brokerRequest);

        Institution guestInstitution = getInstitution(brokerRequest.getGuestInstitutionSchacHome());
        Institution homeInstitution = getInstitution(brokerRequest.getHomeInstitutionSchacHome());

        Map<String, Object> offering = fetchOffering(guestInstitution, brokerRequest);
        //Save the offering as we need it when starting  the actual registration
        request.getSession().setAttribute(OFFERING_SESSION_KEY, offering);

        EnrollmentRequest enrollmentRequest = new EnrollmentRequest(homeInstitution.getPersonsEndpoint(), homeInstitution.getScopes());

        Map<String, Object> result = new HashMap<>();
        result.put("guestInstitution", guestInstitution.sanitize());
        result.put("homeInstitution", homeInstitution.sanitize());
        result.put("authenticationActionUrl", homeInstitution.getAuthenticationEndpoint());
        result.put("enrollmentRequest", enrollmentRequest);
        result.put("offering", offering);
        return result;
    }

    private Institution getInstitution(String institutionSchacHome) {
    	LOG.debug("Lookup institution "+ institutionSchacHome +" in serviceregistry.");
        return serviceRegistry
                .findInstitutionBySchacHome(institutionSchacHome)
                .orElseThrow(() -> {
                    String msg = String.format("Institution %s unknown", institutionSchacHome);
                    LOG.error(msg);
                    return new NotFoundException(msg);
                });
    }

    /*
     * Proxy the call to the actual registration of the enrollment for the user
     */
    @PostMapping("/api/start")
    public Map<String, Object> start(HttpServletRequest request, @RequestBody Map<String, String> correlationMap) {
        if ((boolean) this.featureToggles.get("allowPlayground") && correlationMap.keySet().stream().anyMatch(s -> s.equals("code"))) {
            LOG.debug("Returning playground request with parameters: " + correlationMap);
            return new HashMap<>(correlationMap);
        }
        BrokerRequest brokerRequest = (BrokerRequest) request.getSession().getAttribute(BROKER_REQUEST_SESSION_KEY);
        Map<String, Object> offering = (Map<String, Object>) request.getSession().getAttribute(OFFERING_SESSION_KEY);

        LOG.debug("Received start registration request for brokerRequest: " + brokerRequest);

        try {
            Map<String, Object> body = doStart(brokerRequest, offering, correlationMap);
            request.getSession().invalidate();

            LOG.debug("Returning start registration response " + body + " for brokerRequest: " + brokerRequest);

            return body;
        } catch (Exception e) {
            //Anti-pattern to catch all, but tolerable in this situation
            LOG.error("Unexpected exception", e);

            Institution guestInstitution = getInstitution(brokerRequest.getGuestInstitutionSchacHome());
            Map<String, Object> res = new HashMap<>();
            res.put("message", String.format("Server error at %s", guestInstitution.getName()));
            res.put("code", 500);
            return res;
        }

    }

    private Map<String, Object> doStart(BrokerRequest brokerRequest, Map<String, Object> offering, Map<String, String> correlationMap) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Correlation-ID", correlationMap.get("correlationID"));
        Institution homeInstitution = getInstitution(brokerRequest.getHomeInstitutionSchacHome());
        headers.setBasicAuth(homeInstitution.getRegistrationUser(), homeInstitution.getRegistrationPassword());
        HttpEntity<?> requestEntity = new HttpEntity<>(offering, headers);
        String url = homeInstitution.getRegistrationEndpoint().toString();
        ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, mapRef);
        return responseEntity.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchOffering(Institution guestInstitution, BrokerRequest brokerRequest) {
        String uri = String.format("%s/%s", guestInstitution.getCourseEndpoint(), brokerRequest.getOfferingID());
        CourseAuthentication courseAuthentication = guestInstitution.getCourseAuthentication();

        if (courseAuthentication.equals(CourseAuthentication.NONE)) {
            LOG.debug("Fetching " + uri + " without authentication");
            return restTemplate.getForEntity(uri, Map.class).getBody();
        } else if (courseAuthentication.equals(CourseAuthentication.BASIC)) {
        	LOG.debug("Fetching " + uri + " with basic authentication");
            return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(basicAuthHeaders(guestInstitution)), Map.class).getBody();
        } else {
        	LOG.debug("Fetching " + uri + " with OAUTH authentication");
            return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(accessTokenHeaders()), Map.class).getBody();
        }
    }

    private HttpHeaders basicAuthHeaders(Institution institution) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(institution.getCourseAuthenticationUserName(), institution.getCourseAuthenticationPassword());
        return headers;
    }

    //we don't cache tokens, as the hit-ratio would be extremely low
    private HttpHeaders accessTokenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, secret);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        Map result = restTemplate.exchange(this.tokenEndpoint, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class).getBody();
        String accessToken = (String) result.get("access_token");

        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Bearer " + accessToken);
        return headers;
    }

}
