package org.oasis_open.wemi.context.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;
import org.oasis_open.wemi.context.server.api.SegmentID;
import org.oasis_open.wemi.context.server.api.User;
import org.oasis_open.wemi.context.server.api.services.SegmentService;
import org.oasis_open.wemi.context.server.api.services.UserService;
import org.ops4j.pax.cdi.api.OsgiService;

import javax.inject.Inject;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;

/**
 * A servlet filter to serve a context-specific Javascript containing the current request context object.
 */
@WebFilter(urlPatterns={"/context.js"})
public class ScriptFilter implements Filter {

    public static final String BASE_SCRIPT_LOCATION = "/WEB-INF/javascript/base.js";
    private static final int MAX_COOKIE_AGE_IN_SECONDS = 60*60*24*365*10; // 10-years

    FilterConfig filterConfig;

    @Inject
    @OsgiService
    UserService userService;

    @Inject
    @OsgiService
    SegmentService segmentService;

    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        // first we must retrieve the context for the current visitor, and build a Javascript object to attach to the
        // script output.
        String visitorID = null;
        String httpMethod = null;
        HttpServletRequest httpServletRequest = null;
        if (request instanceof HttpServletRequest) {
            httpServletRequest = (HttpServletRequest) request;
            httpMethod = httpServletRequest.getMethod();
            System.out.println("===================================================================================");
            String sessionId = null;
            if (httpServletRequest.getSession(false) != null) {
                sessionId = httpServletRequest.getSession(false).getId();
            }
            System.out.println(httpMethod + " " + httpServletRequest.getRequestURI() +
                    "?" + httpServletRequest.getQueryString() +
                    " sessionId=" + sessionId +
                    " serverName=" + httpServletRequest.getServerName() +
                    " serverPort=" + httpServletRequest.getServerPort() +
                    " remoteAddr=" + httpServletRequest.getRemoteAddr() +
                    " remotePort=" + httpServletRequest.getRemotePort());
            // dumpRequestHeaders(httpServletRequest);
            Cookie[] cookies = httpServletRequest.getCookies();
            // dumpRequestCookies(cookies);
            for (Cookie cookie : cookies) {
                if ("wemi-profileID".equals(cookie.getName())) {
                    visitorID = cookie.getValue();
                }
            }
        }

        User user = null;
        if ("get".equals(httpMethod.toLowerCase())) {
            if (visitorID == null) {
                // no visitorID cookie was found, we generate a new one and create the user in the user service
                user = createNewUser(visitorID, response);
            } else {
                user = userService.load(visitorID);
                if (user == null) {
                    // this can happen if we have an old cookie but have reset the server.
                    user = createNewUser(visitorID, response);
                }
            }
        }

        if (httpMethod != null && "post".equals(httpMethod.toLowerCase())) {
            // we have received an update on the digitalData structure, we must store it.
            if (request instanceof HttpServletRequest) {
                httpServletRequest = (HttpServletRequest) request;
                String contentType = httpServletRequest.getContentType();
                if (contentType != null && contentType.contains("application/json")) {
                    InputStream jsonInputStream = httpServletRequest.getInputStream();
                    ObjectMapper mapper = new ObjectMapper(); // create once, reuse
                    JsonNode rootNode = mapper.readTree(jsonInputStream);
                    if (rootNode != null) {
                        ObjectNode profileInfo = (ObjectNode) rootNode.get("user").get(0).get("profiles").get(0).get("profileInfo");
                        if (profileInfo != null && user == null && profileInfo.get("profileId") != null) {
                            user = userService.load(profileInfo.get("profileId").asText());
                        }
                        if (user != null) {
                            Iterator<String> fieldNameIter = profileInfo.fieldNames();
                            boolean modifiedProperties = false;
                            while (fieldNameIter.hasNext()) {
                                String fieldName = fieldNameIter.next();
                                JsonNode field = profileInfo.get(fieldName);
                                if (user.hasProperty(fieldName) && user.getProperty(fieldName).equals(field.asText())) {

                                } else {
                                    user.setProperty(fieldName, field.asText());
                                    modifiedProperties = true;
                                }
                            }
                            if (modifiedProperties) {
                                userService.save(user);
                            }
                        } else {
                            // couldn't resolve user !
                        }
                    }
                }
            }
        }

        // @Todo we should here call all plugins to "augment" the user profile. For example we could have LDAP, CRM or Analytics plugins that could add information to the user profile

        if (response instanceof HttpServletResponse) {
            HttpServletResponse httpServletResponse = (HttpServletResponse) response;
            if (httpServletRequest != null) {
                httpServletResponse.setHeader("Access-Control-Allow-Origin", httpServletRequest.getHeader("Origin"));
            } else {
                httpServletResponse.setHeader("Access-Control-Allow-Origin", "*");
            }
            httpServletResponse.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
            httpServletResponse.setHeader("Access-Control-Allow-Credentials", "true");
            httpServletResponse.setHeader("Access-Control-Allow-Methods", "OPTIONS, POST, GET");
            // httpServletResponse.setHeader("Access-Control-Max-Age", "600");
            // httpServletResponse.setHeader("Access-Control-Expose-Headers","Access-Control-Allow-Origin");
            httpServletResponse.flushBuffer();
        }
        Writer responseWriter = response.getWriter();
        if ("post".equals(httpMethod.toLowerCase()) || "get".equals(httpMethod.toLowerCase())) {

            // we re-use the object naming convention from http://www.w3.org/community/custexpdata/, specifically in
            // http://www.w3.org/2013/12/ceddl-201312.pdf
            responseWriter.append("window.digitalData = window.digitalData || {};\n");
            if (user != null) {
                responseWriter.append("var wemiDigitalData = {\n");
                responseWriter.append("  loaded : true, \n");
                responseWriter.append("  user: [ {  \n");
                responseWriter.append("    profiles: [ {  \n");
                responseWriter.append("      profileInfo: { \n");
                responseWriter.append("        profileId: \"" + user.getItemId() + "\",  \n");
                for (String userPropertyName : user.getProperties().stringPropertyNames()) {
                    responseWriter.append("        " + userPropertyName + ": \"" + user.getProperty(userPropertyName) + "\",  \n");
                }
                responseWriter.append("        returningStatus: \"\",  \n");
                responseWriter.append("        type: \"main\",  \n");
                Set<SegmentID> userSegments = segmentService.getSegmentsForUser(user);
                if (userSegments != null && userSegments.size() > 0) {
                    responseWriter.append("        segments: [ \n");
                    int i = 0;
                    for (SegmentID segmentID : userSegments) {
                        responseWriter.append("\"");
                        responseWriter.append(segmentID.getId());
                        responseWriter.append("\"");
                        if (i < userSegments.size() - 1) {
                            responseWriter.append(",  \n");
                        }
                        i++;
                    }
                    responseWriter.append("] \n");
                }
                responseWriter.append("                   } \n");
                responseWriter.append("              } ] \n");
                responseWriter.append("        } ] \n");
                responseWriter.append("}; \n");
            }

            // now we copy the base script source code
            InputStream baseScriptStream = filterConfig.getServletContext().getResourceAsStream(BASE_SCRIPT_LOCATION);
            IOUtils.copy(baseScriptStream, responseWriter);

        } else {
            responseWriter.append("OK");
        }
        responseWriter.flush();

    }

    private void dumpRequestCookies(Cookie[] cookies) {
        System.out.println("Cookies:");
        System.out.println("--------");
        for (Cookie cookie : cookies) {
            System.out.println("  name=" + cookie.getName() +
                    " value=" + cookie.getValue() +
                    " domain=" + cookie.getDomain() +
                    " path=" + cookie.getPath() +
                    " maxAge=" + cookie.getMaxAge() +
                    " httpOnly=" + cookie.isHttpOnly() +
                    " secure=" + cookie.getSecure() +
                    " version=" + cookie.getVersion() +
                    " comment=" + cookie.getComment());
        }
    }

    private void dumpRequestHeaders(HttpServletRequest httpServletRequest) {
        System.out.println("Headers:");
        System.out.println("--------");
        Enumeration<String> headerNameEnum = httpServletRequest.getHeaderNames();
        while (headerNameEnum.hasMoreElements()) {
            String headerName = headerNameEnum.nextElement();
            System.out.println(headerName + ": " + httpServletRequest.getHeader(headerName));
        }
    }

    private User createNewUser(String existingVisitorID, ServletResponse response) {
        User user;
        String visitorID = existingVisitorID;
        if (visitorID == null) {
           visitorID = UUID.randomUUID().toString();
        }
        user = new User(visitorID);
        userService.save(user);
        if (response instanceof HttpServletResponse) {
            HttpServletResponse httpServletResponse = (HttpServletResponse) response;
            Cookie visitorIdCookie = new Cookie("wemi-profileID", user.getItemId());
            visitorIdCookie.setPath("/");
            visitorIdCookie.setMaxAge(MAX_COOKIE_AGE_IN_SECONDS);
            httpServletResponse.addCookie(visitorIdCookie);
        }
        return user;
    }

    public void destroy() {
    }
}
