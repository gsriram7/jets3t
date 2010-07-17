/*
 * JetS3t : Java S3 Toolkit
 * Project hosted at http://bitbucket.org/jmurty/jets3t/
 *
 * Copyright 2006-2010 James Murty
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jets3t.service.impl.rest.httpclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpConnectionManager;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.SimpleHttpConnectionManager;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.auth.CredentialsProvider;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jets3t.service.Constants;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.S3ObjectsChunk;
import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.VersionOrDeleteMarkersChunk;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.impl.rest.HttpException;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser.CopyObjectResultHandler;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser.ListBucketHandler;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser.ListVersionsResultsHandler;
import org.jets3t.service.model.CreateBucketConfiguration;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3BucketLoggingStatus;
import org.jets3t.service.model.S3BucketVersioningStatus;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.S3Owner;
import org.jets3t.service.model.BaseVersionOrDeleteMarker;
import org.jets3t.service.mx.MxDelegate;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.Mimetypes;
import org.jets3t.service.utils.RestUtils;
import org.jets3t.service.utils.ServiceUtils;
import org.jets3t.service.utils.signedurl.SignedUrlHandler;

import com.jamesmurty.utils.XMLBuilder;

/**
 * REST/HTTP implementation of an S3Service based on the
 * <a href="http://jakarta.apache.org/commons/httpclient/">HttpClient</a> library.
 * <p>
 * This class uses properties obtained through {@link Jets3tProperties}. For more information on
 * these properties please refer to
 * <a href="http://jets3t.s3.amazonaws.com/toolkit/configuration.html">JetS3t Configuration</a>
 * </p>
 *
 * @author James Murty
 */
public class RestS3Service extends S3Service implements SignedUrlHandler, AWSRequestAuthorizer {
    private static final long serialVersionUID = -2374187385305273212L;

    public static final String VERSION = "2006-03-01";
    public static final String XML_NAMESPACE = "http://s3.amazonaws.com/doc/" + VERSION + "/";

    private static final Log log = LogFactory.getLog(RestS3Service.class);

    protected HttpClient httpClient = null;
    protected HttpConnectionManager connectionManager = null;
    protected CredentialsProvider credentialsProvider = null;

    protected String defaultStorageClass = null;

    /**
     * Constructs the service and initialises the properties.
     *
     * @param awsCredentials
     * the S3 user credentials to use when communicating with S3, may be null in which case the
     * communication is done as an anonymous user.
     *
     * @throws S3ServiceException
     */
    public RestS3Service(AWSCredentials awsCredentials) throws S3ServiceException {
        this(awsCredentials, null, null);
    }

    /**
     * Constructs the service and initialises the properties.
     *
     * @param awsCredentials
     * the S3 user credentials to use when communicating with S3, may be null in which case the
     * communication is done as an anonymous user.
     * @param invokingApplicationDescription
     * a short description of the application using the service, suitable for inclusion in a
     * user agent string for REST/HTTP requests. Ideally this would include the application's
     * version number, for example: <code>Cockpit/0.7.3</code> or <code>My App Name/1.0</code>
     * @param credentialsProvider
     * an implementation of the HttpClient CredentialsProvider interface, to provide a means for
     * prompting for credentials when necessary.
     *
     * @throws S3ServiceException
     */
    public RestS3Service(AWSCredentials awsCredentials, String invokingApplicationDescription,
        CredentialsProvider credentialsProvider) throws S3ServiceException
    {
        this(awsCredentials, invokingApplicationDescription, credentialsProvider,
            Jets3tProperties.getInstance(Constants.JETS3T_PROPERTIES_FILENAME));
    }

    /**
     * Constructs the service and initialises the properties.
     *
     * @param awsCredentials
     * the S3 user credentials to use when communicating with S3, may be null in which case the
     * communication is done as an anonymous user.
     * @param invokingApplicationDescription
     * a short description of the application using the service, suitable for inclusion in a
     * user agent string for REST/HTTP requests. Ideally this would include the application's
     * version number, for example: <code>Cockpit/0.7.3</code> or <code>My App Name/1.0</code>
     * @param credentialsProvider
     * an implementation of the HttpClient CredentialsProvider interface, to provide a means for
     * prompting for credentials when necessary.
     * @param jets3tProperties
     * JetS3t properties that will be applied within this service.
     *
     * @throws S3ServiceException
     */
    public RestS3Service(AWSCredentials awsCredentials, String invokingApplicationDescription,
        CredentialsProvider credentialsProvider, Jets3tProperties jets3tProperties)
        throws S3ServiceException
    {
        this(awsCredentials, invokingApplicationDescription, credentialsProvider,
            jets3tProperties, new HostConfiguration());
    }

    /**
     * Constructs the service and initialises the properties.
     *
     * @param awsCredentials
     * the S3 user credentials to use when communicating with S3, may be null in which case the
     * communication is done as an anonymous user.
     * @param invokingApplicationDescription
     * a short description of the application using the service, suitable for inclusion in a
     * user agent string for REST/HTTP requests. Ideally this would include the application's
     * version number, for example: <code>Cockpit/0.7.3</code> or <code>My App Name/1.0</code>
     * @param credentialsProvider
     * an implementation of the HttpClient CredentialsProvider interface, to provide a means for
     * prompting for credentials when necessary.
     * @param jets3tProperties
     * JetS3t properties that will be applied within this service.
     * @param hostConfig
     * Custom HTTP host configuration; e.g to register a custom Protocol Socket Factory
     *
     * @throws S3ServiceException
     */
    public RestS3Service(AWSCredentials awsCredentials, String invokingApplicationDescription,
        CredentialsProvider credentialsProvider, Jets3tProperties jets3tProperties,
        HostConfiguration hostConfig) throws S3ServiceException
    {
        super(awsCredentials, invokingApplicationDescription, jets3tProperties);
        this.credentialsProvider = credentialsProvider;

        HttpClientAndConnectionManager initHttpResult = initHttpConnection(hostConfig);
        this.httpClient = initHttpResult.getHttpClient();
        this.connectionManager = initHttpResult.getHttpConnectionManager();

        this.setRequesterPaysEnabled(
            this.jets3tProperties.getBoolProperty("httpclient.requester-pays-buckets-enabled", false));

        this.defaultStorageClass = this.jets3tProperties.getStringProperty(
            "s3service.defaultStorageClass", null);

        // Retrieve Proxy settings.
        if (this.jets3tProperties.getBoolProperty("httpclient.proxy-autodetect", true)) {
            RestUtils.initHttpProxy(httpClient, this.jets3tProperties);
        } else {
            String proxyHostAddress = this.jets3tProperties.getStringProperty("httpclient.proxy-host", null);
            int proxyPort = this.jets3tProperties.getIntProperty("httpclient.proxy-port", -1);
            String proxyUser = this.jets3tProperties.getStringProperty("httpclient.proxy-user", null);
            String proxyPassword = this.jets3tProperties.getStringProperty("httpclient.proxy-password", null);
            String proxyDomain = this.jets3tProperties.getStringProperty("httpclient.proxy-domain", null);
            RestUtils.initHttpProxy(httpClient, this.jets3tProperties,
                proxyHostAddress, proxyPort, proxyUser, proxyPassword, proxyDomain);
        }
    }

    /**
     * Shut down all connections managed by the underlying HttpConnectionManager.
     */
    protected void shutdownImpl() throws S3ServiceException {
        HttpConnectionManager manager = this.getHttpConnectionManager();
        if (manager instanceof SimpleHttpConnectionManager) {
        	((SimpleHttpConnectionManager) manager).shutdown();
        } else if (manager instanceof MultiThreadedHttpConnectionManager) {
        	((MultiThreadedHttpConnectionManager) manager).shutdown();
        } else {
        	manager.closeIdleConnections(0);
        	// Not much else we can do hear, since the HttpConnectionManager
        	// interface doesn't have a #shutdown method.
        }
    }

    /**
     * Initialise HttpClient and HttpConnectionManager objects with the configuration settings
     * appropriate for communicating with S3. By default, this method simply delegates the
     * configuration task to {@link RestUtils#initHttpConnection(AWSRequestAuthorizer,
     * HostConfiguration, Jets3tProperties, String, CredentialsProvider)}.
     * <p>
     * To alter the low-level behaviour of the HttpClient library, override this method in
     * a subclass and apply your own settings before returning the objects.
     *
     * @param hostConfig
     * Custom HTTP host configuration; e.g to register a custom Protocol Socket Factory
     *
     * @return
     * configured HttpClient library client and connection manager objects.
     */
    protected HttpClientAndConnectionManager initHttpConnection(HostConfiguration hostConfig) {
        return RestUtils.initHttpConnection(this, hostConfig, jets3tProperties,
            getInvokingApplicationDescription(), credentialsProvider);
    }

    /**
     * @return
     * the manager of HTTP connections for this service.
     */
    public HttpConnectionManager getHttpConnectionManager() {
        return this.connectionManager;
    }

    /**
     * Replaces the service's default HTTP connection manager.
     * This method should only be used by advanced users.
     *
     * @param httpConnectionManager
     * the connection manager that will replace the default manager created by
     * the class constructor.
     */
    public void setHttpConnectionManager(HttpConnectionManager httpConnectionManager) {
        this.connectionManager = httpConnectionManager;
    }

    /**
     * @return
     * the HTTP client for this service.
     */
    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    /**
     * Replaces the service's default HTTP client.
     * This method should only be used by advanced users.
     *
     * @param httpClient
     * the client that will replace the default client created by
     * the class constructor.
     */
    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * @return
     * the credentials provider this service will use to authenticate itself, or null
     * if no provider is set.
     */
    public CredentialsProvider getCredentialsProvider() {
        return this.credentialsProvider;
    }

    /**
     * Sets the credentials provider this service will use to authenticate itself.
     * Changing the credentials provider with this method will have no effect until
     * the {@link #initHttpConnection(HostConfiguration)} method
     * is called.
     *
     * @param credentialsProvider
     */
    public void setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    /**
     * @param contentType
     * @return true if the given Content-Type string represents an XML document.
     */
    protected boolean isXmlContentType(String contentType) {
        if (contentType != null
            && contentType.toLowerCase().startsWith(Mimetypes.MIMETYPE_XML.toLowerCase()))
        {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Performs an HTTP/S request by invoking the provided HttpMethod object. If the HTTP
     * response code doesn't match the expected value, an exception is thrown.
     *
     * @param httpMethod
     *        the object containing a request target and all other information necessary to perform the
     *        request
     * @param expectedResponseCodes
     *        the HTTP response code(s) that indicates a successful request. If the response code received
     *        does not match this value an error must have occurred, so an exception is thrown.
     * @throws S3ServiceException
     *        all exceptions are wrapped in an S3ServiceException. Depending on the kind of error that
     *        occurred, this exception may contain additional error information available from an XML
     *        error response document.
     */
    protected void performRequest(HttpMethodBase httpMethod, int[] expectedResponseCodes)
        throws S3ServiceException
    {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Performing " + httpMethod.getName()
                    + " request for '" + httpMethod.getURI().toString()
                    + "', expecting response codes: " +
                    "[" + ServiceUtils.join(expectedResponseCodes, ",") + "]");
            }

            // Variables to manage S3 Internal Server 500 or 503 Service Unavailable errors.
            boolean completedWithoutRecoverableError = true;
            int internalErrorCount = 0;
            int requestTimeoutErrorCount = 0;
            int redirectCount = 0;
            boolean wasRecentlyRedirected = false;

            // Perform the request, sleeping and retrying when S3 Internal Errors are encountered.
            int responseCode = -1;
            do {
                // Build the authorization string for the method (Unless we have just been redirected).
                if (!wasRecentlyRedirected) {
                    authorizeHttpRequest(httpMethod);
                } else {
                    // Reset redirection flag
                    wasRecentlyRedirected = false;
                }

                responseCode = httpClient.executeMethod(httpMethod);

                if (responseCode == 307) {
                    // Retry on Temporary Redirects, using new URI from location header
                    authorizeHttpRequest(httpMethod); // Re-authorize *before* we change the URI
                    Header locationHeader = httpMethod.getResponseHeader("location");
                    httpMethod.setURI(new URI(locationHeader.getValue(), true));

                    completedWithoutRecoverableError = false;
                    redirectCount++;
                    wasRecentlyRedirected = true;

                    if (redirectCount > 5) {
                        throw new S3ServiceException("Exceeded 307 redirect limit (5).");
                    }
                } else if (responseCode == 500 || responseCode == 503) {
                    // Retry on S3 Internal Server 500 or 503 Service Unavailable errors.
                    completedWithoutRecoverableError = false;
                    sleepOnInternalError(++internalErrorCount);
                } else {
                    completedWithoutRecoverableError = true;
                }

                String contentType = "";
                if (httpMethod.getResponseHeader("Content-Type") != null) {
                    contentType = httpMethod.getResponseHeader("Content-Type").getValue();
                }

                if (log.isDebugEnabled()) {
                    log.debug("Response for '" + httpMethod.getPath()
                        + "'. Content-Type: " + contentType
                        + ", Headers: " + Arrays.asList(httpMethod.getResponseHeaders()));
                }

                // Check we received the expected result code.
                boolean didReceiveExpectedResponseCode = false;
                for (int i = 0; i < expectedResponseCodes.length && !didReceiveExpectedResponseCode; i++) {
                    if (responseCode == expectedResponseCodes[i]) {
                    	didReceiveExpectedResponseCode = true;
                    }
                }

                if (!didReceiveExpectedResponseCode) {
                    if (log.isWarnEnabled()) {
                        String requestDescription =
                        	httpMethod.getName()
                    		+ " '" + httpMethod.getPath() + "'"
                    		+ " -- ResponseCode: " + httpMethod.getStatusCode()
                    		+ ", ResponseStatus: " + httpMethod.getStatusText()
                        	+ ", Request Headers: [" + ServiceUtils.join(httpMethod.getRequestHeaders(), ", ") + "]"
                        	+ ", Response Headers: [" + ServiceUtils.join(httpMethod.getResponseHeaders(), ", ") + "]";
                        requestDescription = requestDescription.replaceAll("[\\n\\r\\f]", "");  // Remove any newlines.

                        log.warn("Error Response: " + requestDescription);
                    }

                    if (isXmlContentType(contentType)
                        && httpMethod.getResponseBodyAsStream() != null
                        && httpMethod.getResponseContentLength() != 0)
                    {
                        if (log.isDebugEnabled()) {
                            log.debug("Response '" + httpMethod.getPath()
                                + "' - Received error response with XML message");
                        }

                        StringBuffer sb = new StringBuffer();
                        BufferedReader reader = null;
                        try {
                            reader = new BufferedReader(new InputStreamReader(
                                new HttpMethodReleaseInputStream(httpMethod)));
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line + "\n");
                            }
                        } finally {
                            if (reader != null) {
                                reader.close();
                            }
                        }

                        httpMethod.releaseConnection();

                        // Throw exception containing the XML message document.
                        S3ServiceException exception =
                            new S3ServiceException("S3 Error Message.", sb.toString());

                        exception.setResponseHeaders(RestUtils.convertHeadersToMap(
                            	httpMethod.getResponseHeaders()));

                        if ("RequestTimeout".equals(exception.getS3ErrorCode())) {
                            int retryMaxCount = jets3tProperties.getIntProperty("httpclient.retry-max", 5);

                            if (requestTimeoutErrorCount < retryMaxCount) {
                                requestTimeoutErrorCount++;
                                if (log.isWarnEnabled()) {
                                    log.warn("Retrying connection that failed with RequestTimeout error"
                                        + ", attempt number " + requestTimeoutErrorCount + " of "
                                        + retryMaxCount);
                                }
                                completedWithoutRecoverableError = false;
                            } else {
                                if (log.isErrorEnabled()) {
                                    log.error("Exceeded maximum number of retries for RequestTimeout errors: "
                                        + retryMaxCount);
                                }
                                throw exception;
                            }
                        } else if ("RequestTimeTooSkewed".equals(exception.getS3ErrorCode())) {
                            this.timeOffset = RestUtils.getAWSTimeAdjustment();
                            if (log.isWarnEnabled()) {
                                log.warn("Adjusted time offset in response to RequestTimeTooSkewed error. "
                                    + "Local machine and S3 server disagree on the time by approximately "
                                    + (this.timeOffset / 1000) + " seconds. Retrying connection.");
                            }
                            completedWithoutRecoverableError = false;
                        } else if (responseCode == 500 || responseCode == 503) {
                            // Retrying after 500 or 503 error, don't throw exception.
                        } else if (responseCode == 307) {
                            // Retrying after Temporary Redirect 307, don't throw exception.
                            if (log.isDebugEnabled()) {
                                log.debug("Following Temporary Redirect to: " + httpMethod.getURI().toString());
                            }
                        } else {
                            throw exception;
                        }
                    } else {
                        // Consume response content and release connection.
                        String responseText = null;
                        byte[] responseBody = httpMethod.getResponseBody();
                        if (responseBody != null && responseBody.length > 0) {
                            responseText = new String(responseBody);
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("Releasing error response without XML content");
                        }
                        httpMethod.releaseConnection();

                        if (responseCode == 500 || responseCode == 503) {
                            // Retrying after InternalError 500, don't throw exception.
                        } else {
                            // Throw exception containing the HTTP error fields.
                            HttpException httpException = new HttpException(
                            		httpMethod.getStatusCode(), httpMethod.getStatusText());
                            S3ServiceException exception =
                                new S3ServiceException("Request Error"
                            		+ (responseText != null ? " [" + responseText + "]." : "."),
                                	httpException);
                            exception.setResponseHeaders(RestUtils.convertHeadersToMap(
                                	httpMethod.getResponseHeaders()));
                            throw exception;
                        }
                    }
                }
            } while (!completedWithoutRecoverableError);

            // Release immediately any connections without response bodies.
            if ((httpMethod.getResponseBodyAsStream() == null
                || httpMethod.getResponseBodyAsStream().available() == 0)
                && httpMethod.getResponseContentLength() == 0)
            {
                if (log.isDebugEnabled()) {
                    log.debug("Releasing response without content");
                }
                byte[] responseBody = httpMethod.getResponseBody();

                if (responseBody != null && responseBody.length > 0) {
                    throw new S3ServiceException("Oops, too keen to release connection with a non-empty response body");
                }
                httpMethod.releaseConnection();
            }

        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("Releasing HttpClient connection after error: " + t.getMessage());
            }
            httpMethod.releaseConnection();

            S3ServiceException s3ServiceException = null;
            if (t instanceof S3ServiceException) {
                s3ServiceException = (S3ServiceException) t;
            } else {
                MxDelegate.getInstance().registerS3ServiceExceptionEvent();
                s3ServiceException = new S3ServiceException("Request Error.", t);
            }

            // Add S3 request and host IDs from HTTP headers to exception, if they are available
            // and have not already been populated by parsing an XML error response.
            if (!s3ServiceException.isParsedFromXmlMessage()
            	&& httpMethod.getResponseHeader(Constants.AMZ_REQUEST_ID_1) != null
            	&& httpMethod.getResponseHeader(Constants.AMZ_REQUEST_ID_2) != null)
            {
                s3ServiceException.setS3RequestAndHostIds(
                	httpMethod.getResponseHeader(Constants.AMZ_REQUEST_ID_1).getValue(),
                	httpMethod.getResponseHeader(Constants.AMZ_REQUEST_ID_2).getValue());
            }
            s3ServiceException.setRequestVerb(httpMethod.getName());
            s3ServiceException.setRequestPath(httpMethod.getPath());
            try {
                s3ServiceException.setResponseCode(httpMethod.getStatusCode());
                s3ServiceException.setResponseStatus(httpMethod.getStatusText());
            } catch (NullPointerException e) {
                // If no network connection is available, status info is not available
            }
            if (httpMethod.getRequestHeader("Host") != null) {
                s3ServiceException.setRequestHost(
            		httpMethod.getRequestHeader("Host").getValue());
            }
            if (httpMethod.getResponseHeader("Date") != null) {
                s3ServiceException.setResponseDate(
            		httpMethod.getResponseHeader("Date").getValue());
            }
            throw s3ServiceException;
        }
    }

    /**
     * Authorizes an HTTP request by signing it. The signature is based on the target URL, and the
     * signed authorization string is added to the {@link HttpMethod} object as an Authorization header.
     *
     * @param httpMethod
     *        the request object
     * @throws S3ServiceException
     */
    public void authorizeHttpRequest(HttpMethod httpMethod) throws Exception {
        if (getAWSCredentials() != null) {
            if (log.isDebugEnabled()) {
                log.debug("Adding authorization for AWS Access Key '" + getAWSCredentials().getAccessKey() + "'.");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Service has no AWS Credential and is un-authenticated, skipping authorization");
            }
            return;
        }

        String hostname = null;
        try {
            hostname = httpMethod.getURI().getHost();
        } catch (URIException e) {
            if (log.isErrorEnabled()) {
                log.error("Unable to determine hostname target for request", e);
            }
        }

        /*
         * Determine the complete URL for the S3 resource, including any S3-specific parameters.
         */
        String fullUrl = httpMethod.getPath();

        // If we are using an alternative hostname, include the hostname/bucketname in the resource path.
        String s3Endpoint = this.jets3tProperties.getStringProperty(
            "s3service.s3-endpoint", Constants.S3_DEFAULT_HOSTNAME);
        if (!s3Endpoint.equals(hostname)) {
            int subdomainOffset = hostname.lastIndexOf("." + s3Endpoint);
            if (subdomainOffset > 0) {
                // Hostname represents an S3 sub-domain, so the bucket's name is the CNAME portion
                fullUrl = "/" + hostname.substring(0, subdomainOffset) + httpMethod.getPath();
            } else {
                // Hostname represents a virtual host, so the bucket's name is identical to hostname
                fullUrl = "/" + hostname + httpMethod.getPath();
            }
        }

        String queryString = httpMethod.getQueryString();
        if (queryString != null && queryString.length() > 0) {
            fullUrl += "?" + queryString;
        }

        // Set/update the date timestamp to the current time
        // Note that this will be over-ridden if an "x-amz-date" header is present.
        httpMethod.setRequestHeader("Date", ServiceUtils.formatRfc822Date(
            getCurrentTimeWithOffset()));

        // Generate a canonical string representing the operation.
        String canonicalString = RestUtils.makeS3CanonicalString(
                httpMethod.getName(), fullUrl,
                convertHeadersToMap(httpMethod.getRequestHeaders()), null);
        if (log.isDebugEnabled()) {
            log.debug("Canonical string ('|' is a newline): " + canonicalString.replace('\n', '|'));
        }

        // Sign the canonical string.
        String signedCanonical = ServiceUtils.signWithHmacSha1(
            getAWSCredentials().getSecretKey(), canonicalString);

        // Add encoded authorization to connection as HTTP Authorization header.
        String authorizationString = "AWS " + getAWSCredentials().getAccessKey() + ":" + signedCanonical;
        httpMethod.setRequestHeader("Authorization", authorizationString);
    }

    /**
     * Adds all the provided request parameters to a URL in GET request format.
     *
     * @param urlPath
     *        the target URL
     * @param requestParameters
     *        the parameters to add to the URL as GET request params.
     * @return
     * the target URL including the parameters.
     * @throws S3ServiceException
     */
    protected String addRequestParametersToUrlPath(String urlPath, Map requestParameters)
        throws S3ServiceException
    {
        if (requestParameters != null) {
            Iterator reqPropIter = requestParameters.entrySet().iterator();
            while (reqPropIter.hasNext()) {
                Map.Entry entry = (Map.Entry) reqPropIter.next();
                Object key = entry.getKey();
                Object value = entry.getValue();

                urlPath += (urlPath.indexOf("?") < 0 ? "?" : "&")
                    + RestUtils.encodeUrlString(key.toString());
                if (value != null && value.toString().length() > 0) {
                    urlPath += "=" + RestUtils.encodeUrlString(value.toString());
                    if (log.isDebugEnabled()) {
                        log.debug("Added request parameter: " + key + "=" + value);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Added request parameter without value: " + key);
                    }
                }
            }
        }
        return urlPath;
    }

    /**
     * Adds the provided request headers to the connection.
     *
     * @param httpMethod
     *        the connection object
     * @param requestHeaders
     *        the request headers to add as name/value pairs.
     */
    protected void addRequestHeadersToConnection(
            HttpMethodBase httpMethod, Map requestHeaders)
    {
        if (requestHeaders != null) {
            Iterator reqHeaderIter = requestHeaders.entrySet().iterator();
            while (reqHeaderIter.hasNext()) {
                Map.Entry entry = (Map.Entry) reqHeaderIter.next();
                String key = entry.getKey().toString();
                String value = entry.getValue().toString();

                httpMethod.setRequestHeader(key, value);
                if (log.isDebugEnabled()) {
                    log.debug("Added request header to connection: " + key + "=" + value);
                }
            }
        }
    }

    /**
     * Converts an array of Header objects to a map of name/value pairs.
     *
     * @param headers
     * @return
     */
    private Map convertHeadersToMap(Header[] headers) {
        HashMap map = new HashMap();
        for (int i = 0; headers != null && i < headers.length; i++) {
            map.put(headers[i].getName(), headers[i].getValue());
        }
        return map;
    }

    /**
     * Adds all valid metadata name and value pairs as HTTP headers to the given HTTP method.
     * Null metadata names are ignored, as are metadata values that are not of type string.
     * <p>
     * The metadata values are verified to ensure that keys contain only ASCII characters,
     * and that items are not accidentally duplicated due to use of different capitalization.
     * If either of these verification tests fails, an {@link S3ServiceException} is thrown.
     *
     * @param httpMethod
     * @param metadata
     * @throws S3ServiceException
     */
    protected void addMetadataToHeaders(HttpMethodBase httpMethod, Map metadata)
        	throws S3ServiceException
    {
        HashMap headersAlreadySeenMap = new HashMap(metadata.size());

        Iterator metaDataIter = metadata.entrySet().iterator();
        while (metaDataIter.hasNext()) {
            Map.Entry entry = (Map.Entry) metaDataIter.next();
            String key = (String) entry.getKey();
            Object objValue = entry.getValue();

            if (key == null || !(objValue instanceof String)) {
                // Ignore invalid metadata.
                continue;
            }
            String value = (String) objValue;

            // Ensure user-supplied metadata values are compatible with the REST interface.
            // Key must be ASCII text, non-ASCII characters are not allowed in HTTP header names.
            boolean validAscii = false;
            UnsupportedEncodingException encodingException = null;
            try {
            	byte[] asciiBytes = key.getBytes("ASCII");
            	byte[] utf8Bytes = key.getBytes("UTF-8");
            	validAscii = Arrays.equals(asciiBytes, utf8Bytes);
            } catch (UnsupportedEncodingException e) {
            	// Shouldn't ever happen
            	encodingException = e;
            }
            if (!validAscii) {
            	String message =
        			"User metadata name is incompatible with the S3 REST interface, " +
        			"only ASCII characters are allowed in HTTP headers: " + key;
            	if (encodingException == null) {
            		throw new S3ServiceException(message);
            	} else {
            		throw new S3ServiceException(message, encodingException);
            	}
            }

            // Fail early if user-supplied metadata cannot be represented as valid HTTP headers,
            // rather than waiting for a SignatureDoesNotMatch error.
            // NOTE: These checks are very much incomplete.
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new S3ServiceException("The value of metadata item " + key
            		+ " cannot be represented as an HTTP header for the REST S3 interface: "
            		+ value);
            }

            // Ensure each AMZ header is uniquely identified according to the lowercase name.
            String duplicateValue = (String) headersAlreadySeenMap.get(key.toLowerCase());
            if (duplicateValue != null && !duplicateValue.equals(value)) {
            	throw new S3ServiceException(
        			"HTTP header name occurs multiple times in request with different values, " +
        			"probably due to mismatched capitalization when setting metadata names. " +
        			"Duplicate metadata name: '" + key + "', All metadata: " + metadata);
            }

            httpMethod.setRequestHeader(key, value);
            headersAlreadySeenMap.put(key.toLowerCase(), value);
        }
    }

    /**
     * Compares the expected and actual ETag value for an uploaded object, and throws an
     * S3ServiceException if these values do not match.
     *
     * @param expectedETag
     * @param uploadedObject
     * @throws S3ServiceException
     */
    protected void verifyExpectedAndActualETagValues(String expectedETag, S3Object uploadedObject)
    	throws S3ServiceException
    {
        // Compare our locally-calculated hash with the ETag returned by S3.
        if (!expectedETag.equals(uploadedObject.getETag())) {
            throw new S3ServiceException("Mismatch between MD5 hash of uploaded data ("
                + expectedETag + ") and ETag returned by S3 ("
                + uploadedObject.getETag() + ") for object key: "
                + uploadedObject.getKey());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Object upload was automatically verified, the calculated MD5 hash "+
                    "value matched the ETag returned by S3: " + uploadedObject.getKey());
            }
        }
    }

    /**
     * Performs an HTTP HEAD request using the {@link #performRequest} method.
     *
     * @param bucketName
     *        the bucket's name
     * @param objectKey
     *        the object's key name, may be null if the operation is on a bucket only.
     * @param requestParameters
     *        parameters to add to the request URL as GET params
     * @param requestHeaders
     *        headers to add to the request
     * @return
     *        the HTTP method object used to perform the request
     * @throws S3ServiceException
     */
    protected HttpMethodBase performRestHead(String bucketName, String objectKey,
        Map requestParameters, Map requestHeaders) throws S3ServiceException
    {
        HttpMethodBase httpMethod = setupConnection("HEAD", bucketName, objectKey, requestParameters);

        // Add all request headers.
        addRequestHeadersToConnection(httpMethod, requestHeaders);

        performRequest(httpMethod, new int[] {200});

        return httpMethod;
    }

    /**
     * Performs an HTTP GET request using the {@link #performRequest} method.
     *
     * @param bucketName
     *        the bucket's name
     * @param objectKey
     *        the object's key name, may be null if the operation is on a bucket only.
     * @param requestParameters
     *        parameters to add to the request URL as GET params
     * @param requestHeaders
     *        headers to add to the request
     * @return
     *        The HTTP method object used to perform the request.
     *
     * @throws S3ServiceException
     */
    protected HttpMethodBase performRestGet(String bucketName, String objectKey,
        Map requestParameters, Map requestHeaders) throws S3ServiceException
    {
        HttpMethodBase httpMethod = setupConnection("GET", bucketName, objectKey, requestParameters);

        // Add all request headers.
        addRequestHeadersToConnection(httpMethod, requestHeaders);

        int expectedStatusCode = 200;
        if (requestHeaders != null && requestHeaders.containsKey("Range")) {
            // Partial data responses have a status code of 206.
            expectedStatusCode = 206;
        }
        performRequest(httpMethod, new int[] {expectedStatusCode});

        return httpMethod;
    }

    /**
     * Performs an HTTP PUT request using the {@link #performRequest} method.
     *
     * @param bucketName
     *        the name of the bucket the object will be stored in.
     * @param objectKey
     *        the key (name) of the object to be stored.
     * @param metadata
     *        map of name/value pairs to add as metadata to any S3 objects created.
     * @param requestParameters
     *        parameters to add to the request URL as GET params
     * @param requestEntity
     *        an HttpClient object that encapsulates the object and data contents that will be
     *        uploaded. This object supports the resending of object data, when possible.
     * @param autoRelease
     *        if true, the HTTP Method object will be released after the request has
     *        completed and the connection will be closed. If false, the object will
     *        not be released and the caller must take responsibility for doing this.
     * @return
     *        a package including the HTTP method object used to perform the request, and the
     *        content length (in bytes) of the object that was PUT to S3.
     *
     * @throws S3ServiceException
     */
    protected HttpMethodAndByteCount performRestPut(String bucketName, String objectKey,
        Map metadata, Map requestParameters, RequestEntity requestEntity, boolean autoRelease)
        throws S3ServiceException
    {
        // Add any request parameters.
        HttpMethodBase httpMethod = setupConnection("PUT", bucketName, objectKey, requestParameters);

        Map renamedMetadata = RestUtils.renameMetadataKeys(metadata);
        addMetadataToHeaders(httpMethod, renamedMetadata);

        long contentLength = 0;

        if (requestEntity != null) {
            ((PutMethod)httpMethod).setRequestEntity(requestEntity);
        } else {
            // Need an explicit Content-Length even if no data is being uploaded.
            httpMethod.setRequestHeader("Content-Length", "0");
        }

        performRequest(httpMethod, new int[] {200});

        if (requestEntity != null) {
            // Respond with the actual guaranteed content length of the uploaded data.
            contentLength = ((PutMethod)httpMethod).getRequestEntity().getContentLength();
        }

        if (autoRelease) {
            httpMethod.releaseConnection();
        }

        return new HttpMethodAndByteCount(httpMethod, contentLength);
    }

    /**
     * Performs an HTTP DELETE request using the {@link #performRequest} method.
     *
     * @param bucketName
     * the bucket's name
     * @param objectKey
     * the object's key name, may be null if the operation is on a bucket only.
     * @return
     * The HTTP method object used to perform the request.
     *
     * @throws S3ServiceException
     */
    protected HttpMethodBase performRestDelete(String bucketName, String objectKey,
    	Map requestParameters, String multiFactorSerialNumber,
    	String multiFactorAuthCode) throws S3ServiceException
    {
        HttpMethodBase httpMethod = setupConnection("DELETE",
        	bucketName, objectKey, requestParameters);

        // Set Multi-Factor Serial Number and Authentication code if provided.
        if (multiFactorSerialNumber != null || multiFactorAuthCode != null) {
            httpMethod.setRequestHeader(Constants.AMZ_MULTI_FACTOR_AUTH_CODE,
            	multiFactorSerialNumber + " " + multiFactorAuthCode);
        }

        performRequest(httpMethod, new int[] {204, 200});

        // Release connection after DELETE (there's no response content)
        if (log.isDebugEnabled()) {
            log.debug("Releasing HttpMethod after delete");
        }
        httpMethod.releaseConnection();

        return httpMethod;
    }

    protected HttpMethodAndByteCount performRestPutWithXmlBuilder(String bucketName,
    	String objectKey, Map metadata, Map requestParameters, XMLBuilder builder)
        throws S3ServiceException
    {
        try {
        	if (metadata == null) {
        		metadata = new HashMap();
        	}
        	if (!metadata.containsKey("content-type")) {
        		metadata.put("Content-Type", "text/plain");
        	}
        	String xml = builder.asString(null);
            return performRestPut(bucketName, objectKey, metadata, requestParameters,
                new StringRequestEntity(xml, "text/plain", Constants.DEFAULT_ENCODING), true);
    	} catch (Exception e) {
    		if (e instanceof S3ServiceException) {
    			throw (S3ServiceException) e;
    		} else {
    			throw new S3ServiceException("Failed to PUT request containing an XML document", e);
    		}
    	}
    }

    /**
     * Creates an {@link HttpMethod} object to handle a particular connection method.
     *
     * @param method
     *        the HTTP method/connection-type to use, must be one of: PUT, HEAD, GET, DELETE
     * @param bucketName
     *        the bucket's name
     * @param objectKey
     *        the object's key name, may be null if the operation is on a bucket only.
     * @return
     *        the HTTP method object used to perform the request
     *
     * @throws S3ServiceException
     */
    protected HttpMethodBase setupConnection(String method, String bucketName, String objectKey,
    	Map requestParameters) throws S3ServiceException
    {
        if (bucketName == null) {
            throw new S3ServiceException("Cannot connect to S3 Service with a null path");
        }

        boolean disableDnsBuckets = jets3tProperties
            .getBoolProperty("s3service.disable-dns-buckets", false);
        String s3Endpoint = this.jets3tProperties.getStringProperty(
            "s3service.s3-endpoint", Constants.S3_DEFAULT_HOSTNAME);
        String hostname = ServiceUtils.generateS3HostnameForBucket(bucketName, disableDnsBuckets, s3Endpoint);

        // Allow for non-standard virtual directory paths on the server-side
        String virtualPath = this.jets3tProperties.getStringProperty(
            "s3service.s3-endpoint-virtual-path", "");

    	// Determine the resource string (ie the item's path in S3, including the bucket name)
        String resourceString = "/";
        if (hostname.equals(s3Endpoint) && bucketName != null && bucketName.length() > 0) {
            resourceString += bucketName + "/";
        }
        resourceString += (objectKey != null? RestUtils.encodeUrlString(objectKey) : "");

    	// Construct a URL representing a connection for the S3 resource.
        String url = null;
        if (isHttpsOnly()) {
            int securePort = this.jets3tProperties.getIntProperty("s3service.s3-endpoint-https-port", 443);
            url = "https://" + hostname + ":" + securePort + virtualPath + resourceString;
        } else {
            int insecurePort = this.jets3tProperties.getIntProperty("s3service.s3-endpoint-http-port", 80);
            url = "http://" + hostname + ":" + insecurePort + virtualPath + resourceString;
        }
        if (log.isDebugEnabled()) {
            log.debug("S3 URL: " + url);
        }

        // Add additional request parameters to the URL for special cases (eg ACL operations)
        url = addRequestParametersToUrlPath(url, requestParameters);

        HttpMethodBase httpMethod = null;
        if ("PUT".equals(method)) {
            httpMethod = new PutMethod(url);
        } else if ("HEAD".equals(method)) {
            httpMethod = new HeadMethod(url);
        } else if ("GET".equals(method)) {
            httpMethod = new GetMethod(url);
        } else if ("DELETE".equals(method)) {
            httpMethod = new DeleteMethod(url);
        } else {
            throw new IllegalArgumentException("Unrecognised HTTP method name: " + method);
        }

        // Set mandatory Request headers.
        if (httpMethod.getRequestHeader("Date") == null) {
            httpMethod.setRequestHeader("Date", ServiceUtils.formatRfc822Date(
                getCurrentTimeWithOffset()));
        }
        if (httpMethod.getRequestHeader("Content-Type") == null) {
            httpMethod.setRequestHeader("Content-Type", "");
        }

        // Set DevPay request headers.
        if (getDevPayUserToken() != null || getDevPayProductToken() != null) {
            // DevPay tokens have been provided, include these with the request.
            if (getDevPayProductToken() != null) {
                String securityToken = getDevPayUserToken() + "," + getDevPayProductToken();
                httpMethod.setRequestHeader(Constants.AMZ_SECURITY_TOKEN, securityToken);
                if (log.isDebugEnabled()) {
                    log.debug("Including DevPay user and product tokens in request: "
                        + Constants.AMZ_SECURITY_TOKEN + "=" + securityToken);
                }
            } else {
                httpMethod.setRequestHeader(Constants.AMZ_SECURITY_TOKEN, getDevPayUserToken());
                if (log.isDebugEnabled()) {
                    log.debug("Including DevPay user token in request: "
                        + Constants.AMZ_SECURITY_TOKEN + "=" + getDevPayUserToken());
                }
            }
        }

        // Set Requester Pays header to allow access to these buckets.
        if (this.isRequesterPaysEnabled()) {
            String[] requesterPaysHeaderAndValue = Constants.REQUESTER_PAYS_BUCKET_FLAG.split("=");
            httpMethod.setRequestHeader(requesterPaysHeaderAndValue[0], requesterPaysHeaderAndValue[1]);
            if (log.isDebugEnabled()) {
                log.debug("Including Requester Pays header in request: " +
                    Constants.REQUESTER_PAYS_BUCKET_FLAG);
            }
        }

        return httpMethod;
    }


    ////////////////////////////////////////////////////////////////
    // Methods below this point implement S3Service abstract methods
    ////////////////////////////////////////////////////////////////

    public boolean isBucketAccessible(String bucketName) throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Checking existence of bucket: " + bucketName);
        }

        HttpMethodBase httpMethod = null;

        // This request may return an XML document that we're not interested in. Clean this up.
        try {
            // Ensure bucket exists and is accessible by performing a HEAD request
            httpMethod = performRestHead(bucketName, null, null, null);

            if (httpMethod.getResponseBodyAsStream() != null) {
                httpMethod.getResponseBodyAsStream().close();
            }
        } catch (S3ServiceException e) {
            if (log.isDebugEnabled()) {
                log.debug("Bucket does not exist: " + bucketName, e);
            }
            return false;
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to close response body input stream", e);
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("Releasing un-wanted bucket HEAD response");
            }
            if (httpMethod != null) {
                httpMethod.releaseConnection();
            }
        }

        // If we get this far, the bucket exists.
        return true;
    }

    public int checkBucketStatus(String bucketName) throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Checking availability of bucket name: " + bucketName);
        }

        HttpMethodBase httpMethod = null;

        // This request may return an XML document that we're not interested in. Clean this up.
        try {
            // Test bucket's status by performing a HEAD request against it.
            HashMap params = new HashMap();
            params.put("max-keys", "0");
            httpMethod = performRestHead(bucketName, null, params, null);

            if (httpMethod.getResponseBodyAsStream() != null) {
                httpMethod.getResponseBodyAsStream().close();
            }
        } catch (S3ServiceException e) {
            if (e.getResponseCode() == 403) {
                if (log.isDebugEnabled()) {
                    log.debug("Bucket named '" + bucketName + "' already belongs to another S3 user");
                }
                return BUCKET_STATUS__ALREADY_CLAIMED;
            } else if (e.getResponseCode() == 404) {
                if (log.isDebugEnabled()) {
                    log.debug("Bucket does not exist: " + bucketName, e);
                }
                return BUCKET_STATUS__DOES_NOT_EXIST;
            } else {
                throw e;
            }
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to close response body input stream", e);
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("Releasing un-wanted bucket HEAD response");
            }
            if (httpMethod != null) {
                httpMethod.releaseConnection();
            }
        }

        // If we get this far, the bucket exists and you own it.
        return BUCKET_STATUS__MY_BUCKET;
    }

    protected S3Bucket[] listAllBucketsImpl() throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Listing all buckets for AWS user: " + getAWSCredentials().getAccessKey());
        }

        String bucketName = ""; // Root path of S3 service lists the user's buckets.
        HttpMethodBase httpMethod =  performRestGet(bucketName, null, null, null);
        String contentType = httpMethod.getResponseHeader("Content-Type").getValue();

        if (!isXmlContentType(contentType)) {
            throw new S3ServiceException("Expected XML document response from S3 but received content type " +
                contentType);
        }

        S3Bucket[] buckets = (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseListMyBucketsResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getBuckets();
        return buckets;
    }

    protected S3Owner getAccountOwnerImpl() throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Looking up owner of S3 account via the ListAllBuckets response: "
                + getAWSCredentials().getAccessKey());
        }

        String bucketName = ""; // Root path of S3 service lists the user's buckets.
        HttpMethodBase httpMethod =  performRestGet(bucketName, null, null, null);
        String contentType = httpMethod.getResponseHeader("Content-Type").getValue();

        if (!isXmlContentType(contentType)) {
            throw new S3ServiceException("Expected XML document response from S3 but received content type " +
                contentType);
        }

        S3Owner owner = (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseListMyBucketsResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getOwner();
        return owner;
    }


    protected S3Object[] listObjectsImpl(String bucketName, String prefix, String delimiter,
        long maxListingLength) throws S3ServiceException
    {
        return listObjectsInternal(bucketName, prefix, delimiter,
        	maxListingLength, true, null, null).getObjects();
    }

    protected BaseVersionOrDeleteMarker[] listVersionedObjectsImpl(String bucketName,
    	String prefix, String delimiter, String keyMarker, String versionMarker,
    	long maxListingLength) throws S3ServiceException
    {
        return listVersionedObjectsInternal(bucketName, prefix, delimiter,
        	maxListingLength, true, keyMarker, versionMarker).getItems();
    }

    protected S3ObjectsChunk listObjectsChunkedImpl(String bucketName, String prefix, String delimiter,
        long maxListingLength, String priorLastKey, boolean completeListing) throws S3ServiceException
    {
        return listObjectsInternal(bucketName, prefix, delimiter,
        	maxListingLength, completeListing, priorLastKey, null);
    }

    protected VersionOrDeleteMarkersChunk listVersionedObjectsChunkedImpl(String bucketName,
    	String prefix, String delimiter, long maxListingLength, String priorLastKey,
    	String priorLastVersion, boolean completeListing) throws S3ServiceException
    {
        return listVersionedObjectsInternal(bucketName, prefix, delimiter,
        	maxListingLength, completeListing, priorLastKey, priorLastVersion);
    }

    protected S3ObjectsChunk listObjectsInternal(
    	String bucketName, String prefix, String delimiter, long maxListingLength,
    	boolean automaticallyMergeChunks, String priorLastKey, String priorLastVersion)
        throws S3ServiceException
    {
        HashMap parameters = new HashMap();
        if (prefix != null) {
            parameters.put("prefix", prefix);
        }
        if (delimiter != null) {
            parameters.put("delimiter", delimiter);
        }
        if (maxListingLength > 0) {
            parameters.put("max-keys", String.valueOf(maxListingLength));
        }

        ArrayList objects = new ArrayList();
        ArrayList commonPrefixes = new ArrayList();

        boolean incompleteListing = true;
        int ioErrorRetryCount = 0;

        while (incompleteListing) {
            if (priorLastKey != null) {
                parameters.put("marker", priorLastKey);
            } else {
                parameters.remove("marker");
            }

            HttpMethodBase httpMethod = performRestGet(bucketName, null, parameters, null);
            ListBucketHandler listBucketHandler = null;

            try {
                listBucketHandler = (new XmlResponsesSaxParser(this.jets3tProperties))
                    .parseListBucketResponse(
                        new HttpMethodReleaseInputStream(httpMethod));
                ioErrorRetryCount = 0;
            } catch (S3ServiceException e) {
                if (e.getCause() instanceof IOException && ioErrorRetryCount < 5) {
                    ioErrorRetryCount++;
                    if (log.isWarnEnabled()) {
                        log.warn("Retrying bucket listing failure due to IO error", e);
                    }
                    continue;
                } else {
                    throw e;
                }
            }

            S3Object[] partialObjects = listBucketHandler.getObjects();
            if (log.isDebugEnabled()) {
                log.debug("Found " + partialObjects.length + " objects in one batch");
            }
            objects.addAll(Arrays.asList(partialObjects));

            String[] partialCommonPrefixes = listBucketHandler.getCommonPrefixes();
            if (log.isDebugEnabled()) {
                log.debug("Found " + partialCommonPrefixes.length + " common prefixes in one batch");
            }
            commonPrefixes.addAll(Arrays.asList(partialCommonPrefixes));

            incompleteListing = listBucketHandler.isListingTruncated();
            if (incompleteListing) {
                priorLastKey = listBucketHandler.getMarkerForNextListing();
                if (log.isDebugEnabled()) {
                    log.debug("Yet to receive complete listing of bucket contents, "
                        + "last key for prior chunk: " + priorLastKey);
                }
            } else {
                priorLastKey = null;
            }

            if (!automaticallyMergeChunks) {
                break;
            }
        }
        if (automaticallyMergeChunks) {
            if (log.isDebugEnabled()) {
                log.debug("Found " + objects.size() + " objects in total");
            }
            return new S3ObjectsChunk(
                prefix, delimiter,
                (S3Object[]) objects.toArray(new S3Object[objects.size()]),
                (String[]) commonPrefixes.toArray(new String[commonPrefixes.size()]),
                null);
        } else {
            return new S3ObjectsChunk(
                prefix, delimiter,
                (S3Object[]) objects.toArray(new S3Object[objects.size()]),
                (String[]) commonPrefixes.toArray(new String[commonPrefixes.size()]),
                priorLastKey);
        }
    }

    protected VersionOrDeleteMarkersChunk listVersionedObjectsInternal(
    	String bucketName, String prefix, String delimiter, long maxListingLength,
    	boolean automaticallyMergeChunks, String nextKeyMarker, String nextVersionIdMarker)
        throws S3ServiceException
    {
        HashMap parameters = new HashMap();
        parameters.put("versions", null);
        if (prefix != null) {
            parameters.put("prefix", prefix);
        }
        if (delimiter != null) {
            parameters.put("delimiter", delimiter);
        }
        if (maxListingLength > 0) {
            parameters.put("max-keys", String.valueOf(maxListingLength));
        }

        ArrayList items = new ArrayList();
        ArrayList commonPrefixes = new ArrayList();

        boolean incompleteListing = true;
        int ioErrorRetryCount = 0;

        while (incompleteListing) {
            if (nextKeyMarker != null) {
                parameters.put("key-marker", nextKeyMarker);
            } else {
                parameters.remove("key-marker");
            }
            if (nextVersionIdMarker != null) {
                parameters.put("version-id-marker", nextVersionIdMarker);
            } else {
                parameters.remove("version-id-marker");
            }

            HttpMethodBase httpMethod = performRestGet(bucketName, null, parameters, null);
            ListVersionsResultsHandler handler = null;

            try {
                handler = (new XmlResponsesSaxParser(this.jets3tProperties))
                    .parseListVersionsResponse(
                        new HttpMethodReleaseInputStream(httpMethod));
                ioErrorRetryCount = 0;
            } catch (S3ServiceException e) {
                if (e.getCause() instanceof IOException && ioErrorRetryCount < 5) {
                    ioErrorRetryCount++;
                    if (log.isWarnEnabled()) {
                        log.warn("Retrying bucket listing failure due to IO error", e);
                    }
                    continue;
                } else {
                    throw e;
                }
            }

            BaseVersionOrDeleteMarker[] partialItems = handler.getItems();
            if (log.isDebugEnabled()) {
                log.debug("Found " + partialItems.length + " items in one batch");
            }
            items.addAll(Arrays.asList(partialItems));

            String[] partialCommonPrefixes = handler.getCommonPrefixes();
            if (log.isDebugEnabled()) {
                log.debug("Found " + partialCommonPrefixes.length + " common prefixes in one batch");
            }
            commonPrefixes.addAll(Arrays.asList(partialCommonPrefixes));

            incompleteListing = handler.isListingTruncated();
            nextKeyMarker = handler.getNextKeyMarker();
            nextVersionIdMarker = handler.getNextVersionIdMarker();
            if (incompleteListing) {
                if (log.isDebugEnabled()) {
                    log.debug("Yet to receive complete listing of bucket versions, "
                        + "continuing with key-marker=" + nextKeyMarker
                        + " and version-id-marker=" + nextVersionIdMarker);
                }
            }

            if (!automaticallyMergeChunks) {
                break;
            }
        }
        if (automaticallyMergeChunks) {
            if (log.isDebugEnabled()) {
                log.debug("Found " + items.size() + " items in total");
            }
            return new VersionOrDeleteMarkersChunk(
                prefix, delimiter,
                (BaseVersionOrDeleteMarker[]) items.toArray(new BaseVersionOrDeleteMarker[items.size()]),
                (String[]) commonPrefixes.toArray(new String[commonPrefixes.size()]),
                null, null);
        } else {
            return new VersionOrDeleteMarkersChunk(
                prefix, delimiter,
                (BaseVersionOrDeleteMarker[]) items.toArray(new BaseVersionOrDeleteMarker[items.size()]),
                (String[]) commonPrefixes.toArray(new String[commonPrefixes.size()]),
                nextKeyMarker, nextVersionIdMarker);
        }
    }

    protected void deleteObjectImpl(String bucketName, String objectKey,
    	String versionId, String multiFactorSerialNumber, String multiFactorAuthCode)
        throws S3ServiceException
    {
        Map requestParameters = new HashMap();
        if (versionId != null) {
        	requestParameters.put("versionId", versionId);
        }
        performRestDelete(bucketName, objectKey, requestParameters,
        	multiFactorSerialNumber, multiFactorAuthCode);
    }

    protected AccessControlList getObjectAclImpl(String bucketName, String objectKey)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving Access Control List for bucketName="
            	+ bucketName + ", objectKkey=" + objectKey);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("acl","");

        HttpMethodBase httpMethod = performRestGet(bucketName, objectKey, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseAccessControlListResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getAccessControlList();
    }

    protected AccessControlList getObjectAclImpl(String bucketName, String objectKey,
    	String versionId) throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving versioned Access Control List for bucketName="
            	+ bucketName + ", objectKkey=" + objectKey);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("acl","");
        if (versionId != null) {
        	requestParameters.put("versionId", versionId);
        }

        HttpMethodBase httpMethod = performRestGet(bucketName, objectKey, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseAccessControlListResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getAccessControlList();
    }

    protected AccessControlList getBucketAclImpl(String bucketName) throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving Access Control List for Bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("acl","");

        HttpMethodBase httpMethod = performRestGet(bucketName, null, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseAccessControlListResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getAccessControlList();
    }

    protected void putObjectAclImpl(String bucketName, String objectKey, AccessControlList acl,
    	String versionId) throws S3ServiceException
    {
        putAclImpl(bucketName, objectKey, acl, versionId);
    }

    protected void putBucketAclImpl(String bucketName, AccessControlList acl)
        throws S3ServiceException
    {
        String fullKey = bucketName;
        putAclImpl(fullKey, null, acl, null);
    }

    protected void putAclImpl(String bucketName, String objectKey, AccessControlList acl,
        String versionId) throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Setting Access Control List for bucketName=" + bucketName + ", objectKey=" + objectKey);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("acl","");
        if (versionId != null) {
            requestParameters.put("versionId", versionId);
        }

        HashMap metadata = new HashMap();
        metadata.put("Content-Type", "text/plain");

        try {
            String aclAsXml = acl.toXml();
            metadata.put("Content-Length", String.valueOf(aclAsXml.length()));
            performRestPut(bucketName, objectKey, metadata, requestParameters,
                new StringRequestEntity(aclAsXml, "text/plain", Constants.DEFAULT_ENCODING),
                true);
        } catch (UnsupportedEncodingException e) {
            throw new S3ServiceException("Unable to encode ACL XML document", e);
        }
    }

    protected S3Bucket createBucketImpl(String bucketName, String location, AccessControlList acl)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Creating bucket with name: " + bucketName);
        }

        HashMap metadata = new HashMap();
        RequestEntity requestEntity = null;

        if (location != null && !"US".equalsIgnoreCase(location)) {
            metadata.put("Content-Type", "text/xml");
            try {
                CreateBucketConfiguration config = new CreateBucketConfiguration(location);
                metadata.put("Content-Length", String.valueOf(config.toXml().length()));
                requestEntity = new StringRequestEntity(config.toXml(), "text/xml", Constants.DEFAULT_ENCODING);
            } catch (UnsupportedEncodingException e) {
                throw new S3ServiceException("Unable to encode CreateBucketConfiguration XML document", e);
            }
        }

        Map map = createObjectImpl(bucketName, null, null, requestEntity, metadata, acl, null);

        S3Bucket bucket = new S3Bucket(bucketName, location);
        bucket.setAcl(acl);
        bucket.replaceAllMetadata(map);
        return bucket;
    }

    protected void deleteBucketImpl(String bucketName) throws S3ServiceException {
        performRestDelete(bucketName, null, null, null, null);
    }

    protected void updateBucketVersioningStatusImpl(String bucketName,
    	boolean enabled, boolean multiFactorAuthDeleteEnabled,
    	String multiFactorSerialNumber, String multiFactorAuthCode)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug( (enabled ? "Enabling" : "Suspending")
            	+ " versioning for bucket " + bucketName
            	+ (multiFactorAuthDeleteEnabled ? " with Multi-Factor Auth enabled" : ""));
        }
    	try {
    		XMLBuilder builder = XMLBuilder
    			.create("VersioningConfiguration").a("xmlns", XML_NAMESPACE)
    				.e("Status").t( (enabled ? "Enabled" : "Suspended") ).up()
    			    .e("MfaDelete").t( (multiFactorAuthDeleteEnabled ? "Enabled" : "Disabled"));
    		Map requestParams = new HashMap();
    		requestParams.put("versioning", null);
    		Map metadata = new HashMap();
    		if (multiFactorSerialNumber != null || multiFactorAuthCode != null) {
    			metadata.put(Constants.AMZ_MULTI_FACTOR_AUTH_CODE,
    				multiFactorSerialNumber + " " + multiFactorAuthCode);
    		}
            performRestPutWithXmlBuilder(bucketName, null, metadata, requestParams, builder);
    	} catch (ParserConfigurationException e) {
    		throw new S3ServiceException("Failed to build XML document for request", e);
    	}
    }

    protected S3BucketVersioningStatus getBucketVersioningStatusImpl(String bucketName)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug( "Checking status of versioning for bucket " + bucketName);
        }
    	Map requestParams = new HashMap();
    	requestParams.put("versioning", null);
        HttpMethodBase method = performRestGet(bucketName, null, requestParams, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseVersioningConfigurationResponse(new HttpMethodReleaseInputStream(method));
    }

    /**
     * Beware of high memory requirements when creating large S3 objects when the Content-Length
     * is not set in the object.
     */
    protected S3Object putObjectImpl(String bucketName, S3Object object) throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Creating Object with key " + object.getKey() + " in bucket " + bucketName);
        }

        // We do not need to calculate the data MD5 hash during upload if the
        // expected hash value was provided as the object's Content-MD5 header.
        boolean isLiveMD5HashingRequired =
            (object.getMetadata(S3Object.METADATA_HEADER_CONTENT_MD5) == null);

        RequestEntity requestEntity = null;
        if (object.getDataInputStream() != null) {
            if (object.containsMetadata(S3Object.METADATA_HEADER_CONTENT_LENGTH)) {
                if (log.isDebugEnabled()) {
                    log.debug("Uploading object data with Content-Length: " + object.getContentLength());
                }
                requestEntity = new RepeatableRequestEntity(object.getKey(),
                    object.getDataInputStream(), object.getContentType(), object.getContentLength(),
                    this.jets3tProperties, isLiveMD5HashingRequired);
            } else {
                // Use InputStreamRequestEntity for objects with an unknown content length, as the
                // entity will cache the results and doesn't need to know the data length in advance.
                if (log.isWarnEnabled()) {
                    log.warn("Content-Length of data stream not set, will automatically determine data length in memory");
                }
                requestEntity = new InputStreamRequestEntity(
                    object.getDataInputStream(), InputStreamRequestEntity.CONTENT_LENGTH_AUTO);
            }
        }

        this.pubObjectWithRequestEntityImpl(bucketName, object, requestEntity);

        return object;
    }

    protected void pubObjectWithRequestEntityImpl(String bucketName, S3Object object,
        RequestEntity requestEntity) throws S3ServiceException
    {
        // We do not need to calculate the data MD5 hash during upload if the
        // expected hash value was provided as the object's Content-MD5 header.
        boolean isLiveMD5HashingRequired =
            (object.getMetadata(S3Object.METADATA_HEADER_CONTENT_MD5) == null);

        Map map = createObjectImpl(bucketName, object.getKey(), object.getContentType(),
            requestEntity, object.getMetadataMap(), object.getAcl(), object.getStorageClass());

        try {
            object.closeDataInputStream();
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to close data input stream for object '" + object.getKey() + "'", e);
            }
        }

        // Populate object with result metadata.
        object.replaceAllMetadata(map);

        // Confirm that the data was not corrupted in transit by checking S3's calculated
        // hash value with the locally computed value. This is only necessary if the user
        // did not provide a Content-MD5 header with the original object.
        // Note that we can only confirm the data if we used a RepeatableRequestEntity to
        // upload it, if the user did not provide a content length with the original
        // object we are SOL.
        if (isLiveMD5HashingRequired && requestEntity instanceof RepeatableRequestEntity) {
            // Obtain locally-calculated MD5 hash from request entity.
            String hexMD5OfUploadedData = ServiceUtils.toHex(
                ((RepeatableRequestEntity)requestEntity).getMD5DigestOfData());
            verifyExpectedAndActualETagValues(hexMD5OfUploadedData, object);
        }
    }

    protected Map createObjectImpl(String bucketName, String objectKey, String contentType,
        RequestEntity requestEntity, Map metadata, AccessControlList acl, String storageClass)
        throws S3ServiceException
    {
        if (metadata == null) {
            metadata = new HashMap();
        } else {
            // Use a new map object in case the one we were provided is immutable.
            metadata = new HashMap(metadata);
        }
        if (contentType != null) {
            metadata.put("Content-Type", contentType);
        } else {
            metadata.put("Content-Type", Mimetypes.MIMETYPE_OCTET_STREAM);
        }

        // Apply per-object or default storage class when uploading object
        if (this.jets3tProperties.getBoolProperty("s3service.enable-storage-classes", true)) {
            if (storageClass == null && this.defaultStorageClass != null) {
                // Apply default storage class
                storageClass = this.defaultStorageClass;
                log.debug("Applied default storage class '" + storageClass
                    + "' to object '" + objectKey + "'");
            }
            if (storageClass != null) {
                metadata.put("x-amz-storage-class", storageClass);
            }
        }

        boolean putNonStandardAcl = false;
        if (acl != null) {
            if (AccessControlList.REST_CANNED_PRIVATE.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "private");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "public-read");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ_WRITE.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "public-read-write");
            } else if (AccessControlList.REST_CANNED_AUTHENTICATED_READ.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "authenticated-read");
            } else {
                putNonStandardAcl = true;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Creating object bucketName=" + bucketName +
                ", objectKey=" + objectKey +
                ", storageClass=" + storageClass + "." +
                " Content-Type=" + metadata.get("Content-Type") +
                " Including data? " + (requestEntity != null) +
                " Metadata: " + metadata +
                " ACL: " + acl
                );
        }

        HttpMethodAndByteCount methodAndByteCount = performRestPut(
            bucketName, objectKey, metadata, null, requestEntity, true);

        // Consume response content.
        HttpMethodBase httpMethod = methodAndByteCount.getHttpMethod();

        Map map = new HashMap();
        map.putAll(metadata); // Keep existing metadata.
        map.putAll(convertHeadersToMap(httpMethod.getResponseHeaders()));
        map.put(S3Object.METADATA_HEADER_CONTENT_LENGTH, String.valueOf(methodAndByteCount.getByteCount()));
        map = ServiceUtils.cleanRestMetadataMap(map);

        if (putNonStandardAcl) {
            if (log.isDebugEnabled()) {
                log.debug("Creating object with a non-canned ACL using REST, so an extra ACL Put is required");
            }
            putAclImpl(bucketName, objectKey, acl, null);
        }

        return map;
    }

    protected Map copyObjectImpl(String sourceBucketName, String sourceObjectKey,
        String destinationBucketName, String destinationObjectKey,
        AccessControlList acl, Map destinationMetadata, Calendar ifModifiedSince,
        Calendar ifUnmodifiedSince, String[] ifMatchTags, String[] ifNoneMatchTags,
        String versionId, String destinationObjectStorageClass)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Copying Object from " + sourceBucketName + ":" + sourceObjectKey
                + " to " + destinationBucketName + ":" + destinationObjectKey);
        }

        Map metadata = new HashMap();

        String sourceKey = RestUtils.encodeUrlString(sourceBucketName + "/" + sourceObjectKey);
        if (versionId != null) {
            sourceKey += "?versionId=" + versionId;
        }

        metadata.put("x-amz-copy-source", sourceKey);

        boolean enableStorageClasses = this.jets3tProperties.getBoolProperty(
            "s3service.enable-storage-classes", true);
        if (enableStorageClasses && destinationObjectStorageClass != null) {
            metadata.put("x-amz-storage-class", destinationObjectStorageClass);
        }

        if (destinationMetadata != null) {
            metadata.put("x-amz-metadata-directive", "REPLACE");
            // Include any metadata provided with S3 object.
            metadata.putAll(destinationMetadata);
            // Set default content type.
            if (!metadata.containsKey("Content-Type")) {
                metadata.put("Content-Type", Mimetypes.MIMETYPE_OCTET_STREAM);
            }
        } else {
            metadata.put("x-amz-metadata-directive", "COPY");
        }

        boolean putNonStandardAcl = false;
        if (acl != null) {
            if (AccessControlList.REST_CANNED_PRIVATE.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "private");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "public-read");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ_WRITE.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "public-read-write");
            } else if (AccessControlList.REST_CANNED_AUTHENTICATED_READ.equals(acl)) {
                metadata.put(Constants.REST_HEADER_PREFIX + "acl", "authenticated-read");
            } else {
                putNonStandardAcl = true;
            }
        }

        if (ifModifiedSince != null) {
            metadata.put("x-amz-copy-source-if-modified-since",
                ServiceUtils.formatRfc822Date(ifModifiedSince.getTime()));
            if (log.isDebugEnabled()) {
                log.debug("Only copy object if-modified-since:" + ifModifiedSince);
            }
        }
        if (ifUnmodifiedSince != null) {
            metadata.put("x-amz-copy-source-if-unmodified-since",
                ServiceUtils.formatRfc822Date(ifUnmodifiedSince.getTime()));
            if (log.isDebugEnabled()) {
                log.debug("Only copy object if-unmodified-since:" + ifUnmodifiedSince);
            }
        }
        if (ifMatchTags != null) {
            String tags = ServiceUtils.join(ifMatchTags, ",");
            metadata.put("x-amz-copy-source-if-match", tags);
            if (log.isDebugEnabled()) {
                log.debug("Only copy object based on hash comparison if-match:" + tags);
            }
        }
        if (ifNoneMatchTags != null) {
            String tags = ServiceUtils.join(ifNoneMatchTags, ",");
            metadata.put("x-amz-copy-source-if-none-match", tags);
            if (log.isDebugEnabled()) {
                log.debug("Only copy object based on hash comparison if-none-match:" + tags);
            }
        }

        HttpMethodAndByteCount methodAndByteCount = performRestPut(
            destinationBucketName, destinationObjectKey, metadata, null, null, false);

        CopyObjectResultHandler handler = (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseCopyObjectResponse(
                new HttpMethodReleaseInputStream(methodAndByteCount.getHttpMethod()));

        // Release HTTP connection manually. This should already have been done by the
        // HttpMethodReleaseInputStream class, but you can never be too sure...
        methodAndByteCount.getHttpMethod().releaseConnection();

        if (handler.isErrorResponse()) {
            throw new S3ServiceException(
                "Copy failed: Code=" + handler.getErrorCode() +
                ", Message=" + handler.getErrorMessage() +
                ", RequestId=" + handler.getErrorRequestId() +
                ", HostId=" + handler.getErrorHostId());
        }

        Map map = new HashMap();

        // Result fields returned when copy is successful.
        map.put("Last-Modified", handler.getLastModified());
        map.put("ETag", handler.getETag());

        // Include response headers in result map.
        map.putAll(convertHeadersToMap(methodAndByteCount.getHttpMethod().getResponseHeaders()));
        map = ServiceUtils.cleanRestMetadataMap(map);

        if (putNonStandardAcl) {
            if (log.isDebugEnabled()) {
                log.debug("Creating object with a non-canned ACL using REST, so an extra ACL Put is required");
            }
            putAclImpl(destinationBucketName, destinationObjectKey, acl, null);
        }

        return map;
    }

    protected S3Object getObjectDetailsImpl(String bucketName, String objectKey,
    	Calendar ifModifiedSince, Calendar ifUnmodifiedSince,
    	String[] ifMatchTags, String[] ifNoneMatchTags, String versionId)
        throws S3ServiceException
    {
        return getObjectImpl(true, bucketName, objectKey,
            ifModifiedSince, ifUnmodifiedSince, ifMatchTags, ifNoneMatchTags, null, null,
            versionId);
    }

    protected S3Object getObjectImpl(String bucketName, String objectKey,
    	Calendar ifModifiedSince, Calendar ifUnmodifiedSince,
    	String[] ifMatchTags, String[] ifNoneMatchTags,
    	Long byteRangeStart, Long byteRangeEnd, String versionId)
        throws S3ServiceException
    {
        return getObjectImpl(false, bucketName, objectKey, ifModifiedSince, ifUnmodifiedSince,
            ifMatchTags, ifNoneMatchTags, byteRangeStart, byteRangeEnd, versionId);
    }

    private S3Object getObjectImpl(boolean headOnly, String bucketName, String objectKey,
        Calendar ifModifiedSince, Calendar ifUnmodifiedSince, String[] ifMatchTags,
        String[] ifNoneMatchTags, Long byteRangeStart, Long byteRangeEnd, String versionId)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving " + (headOnly? "Head" : "All")
            	+ " information for bucket " + bucketName + " and object " + objectKey);
        }

        HashMap requestHeaders = new HashMap();
        HashMap requestParameters = new HashMap();

        if (ifModifiedSince != null) {
            requestHeaders.put("If-Modified-Since",
                ServiceUtils.formatRfc822Date(ifModifiedSince.getTime()));
            if (log.isDebugEnabled()) {
                log.debug("Only retrieve object if-modified-since:" + ifModifiedSince);
            }
        }
        if (ifUnmodifiedSince != null) {
            requestHeaders.put("If-Unmodified-Since",
                ServiceUtils.formatRfc822Date(ifUnmodifiedSince.getTime()));
            if (log.isDebugEnabled()) {
                log.debug("Only retrieve object if-unmodified-since:" + ifUnmodifiedSince);
            }
        }
        if (ifMatchTags != null) {
            String tags = ServiceUtils.join(ifMatchTags, ",");
            requestHeaders.put("If-Match", tags);
            if (log.isDebugEnabled()) {
                log.debug("Only retrieve object based on hash comparison if-match:" + tags);
            }
        }
        if (ifNoneMatchTags != null) {
            String tags = ServiceUtils.join(ifNoneMatchTags, ",");
            requestHeaders.put("If-None-Match", tags);
            if (log.isDebugEnabled()) {
                log.debug("Only retrieve object based on hash comparison if-none-match:" + tags);
            }
        }
        if (byteRangeStart != null || byteRangeEnd != null) {
            String range = "bytes="
                + (byteRangeStart != null? byteRangeStart.toString() : "")
                + "-"
                + (byteRangeEnd != null? byteRangeEnd.toString() : "");
            requestHeaders.put("Range", range);
            if (log.isDebugEnabled()) {
                log.debug("Only retrieve object if it is within range:" + range);
            }
        }
        if (versionId != null) {
            requestParameters.put("versionId", versionId);
        }

        HttpMethodBase httpMethod = null;
        if (headOnly) {
            httpMethod = performRestHead(bucketName, objectKey, requestParameters, requestHeaders);
        } else {
            httpMethod = performRestGet(bucketName, objectKey, requestParameters, requestHeaders);
        }

        HashMap map = new HashMap();
        map.putAll(convertHeadersToMap(httpMethod.getResponseHeaders()));

        S3Object responseObject = new S3Object(objectKey);
        responseObject.setBucketName(bucketName);
        responseObject.replaceAllMetadata(ServiceUtils.cleanRestMetadataMap(map));
        responseObject.setMetadataComplete(true); // Flag this object as having the complete metadata set.
        if (!headOnly) {
            HttpMethodReleaseInputStream releaseIS = new HttpMethodReleaseInputStream(httpMethod);
            responseObject.setDataInputStream(releaseIS);
        } else {
            // Release connection after HEAD (there's no response content)
            if (log.isDebugEnabled()) {
                log.debug("Releasing HttpMethod after HEAD");
            }
            httpMethod.releaseConnection();
        }

        return responseObject;
    }

    protected String getBucketLocationImpl(String bucketName)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving location of Bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("location","");

        HttpMethodBase httpMethod = performRestGet(bucketName, null, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseBucketLocationResponse(
                new HttpMethodReleaseInputStream(httpMethod));
    }

    protected S3BucketLoggingStatus getBucketLoggingStatusImpl(String bucketName)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving Logging Status for Bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("logging","");

        HttpMethodBase httpMethod = performRestGet(bucketName, null, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseLoggingStatusResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getBucketLoggingStatus();
    }

    protected void setBucketLoggingStatusImpl(String bucketName, S3BucketLoggingStatus status)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Setting Logging Status for bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("logging","");

        HashMap metadata = new HashMap();
        metadata.put("Content-Type", "text/plain");

        try {
            String statusAsXml = status.toXml();
            metadata.put("Content-Length", String.valueOf(statusAsXml.length()));
            performRestPut(bucketName, null, metadata, requestParameters,
                new StringRequestEntity(statusAsXml, "text/plain", Constants.DEFAULT_ENCODING),
                true);
        } catch (UnsupportedEncodingException e) {
            throw new S3ServiceException("Unable to encode LoggingStatus XML document", e);
        }
    }

    protected boolean isRequesterPaysBucketImpl(String bucketName)
        throws S3ServiceException
    {
        if (log.isDebugEnabled()) {
            log.debug("Retrieving Request Payment Configuration settings for Bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("requestPayment","");

        HttpMethodBase httpMethod = performRestGet(bucketName, null, requestParameters, null);
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseRequestPaymentConfigurationResponse(
                new HttpMethodReleaseInputStream(httpMethod));
    }

    protected void setRequesterPaysBucketImpl(String bucketName, boolean requesterPays) throws S3ServiceException {
        if (log.isDebugEnabled()) {
            log.debug("Setting Request Payment Configuration settings for bucket: " + bucketName);
        }

        HashMap requestParameters = new HashMap();
        requestParameters.put("requestPayment","");

        HashMap metadata = new HashMap();
        metadata.put("Content-Type", "text/plain");

        try {
            String xml =
                "<RequestPaymentConfiguration xmlns=\"" + Constants.XML_NAMESPACE + "\">" +
                    "<Payer>" +
                        (requesterPays ? "Requester" : "BucketOwner") +
                    "</Payer>" +
                "</RequestPaymentConfiguration>";

            metadata.put("Content-Length", String.valueOf(xml.length()));
            performRestPut(bucketName, null, metadata, requestParameters,
                new StringRequestEntity(xml, "text/plain", Constants.DEFAULT_ENCODING),
                true);
        } catch (UnsupportedEncodingException e) {
            throw new S3ServiceException("Unable to encode RequestPaymentConfiguration XML document", e);
        }
    }

    /**
     * Puts an object using a pre-signed PUT URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     * <p>
     * This operation does not required any S3 functionality as it merely
     * uploads the object by performing a standard HTTP PUT using the signed URL.
     *
     * @param signedPutUrl
     * a signed PUT URL.
     * @param object
     * the object to upload, which must correspond to the object for which the URL was signed.
     * The object <b>must</b> have the correct content length set, and to apply a non-standard
     * ACL policy only the REST canned ACLs can be used
     * (eg {@link AccessControlList#REST_CANNED_PUBLIC_READ_WRITE}).
     *
     * @return
     * the S3Object put to S3. The S3Object returned will represent the object created in S3.
     *
     * @throws S3ServiceException
     */
    public S3Object putObjectWithSignedUrl(String signedPutUrl, S3Object object) throws S3ServiceException {
        PutMethod putMethod = new PutMethod(signedPutUrl);

        Map renamedMetadata = RestUtils.renameMetadataKeys(object.getMetadataMap());
        addMetadataToHeaders(putMethod, renamedMetadata);

        if (!object.containsMetadata("Content-Length")) {
            throw new IllegalStateException("Content-Length must be specified for objects put using signed PUT URLs");
        }

        RepeatableRequestEntity repeatableRequestEntity = null;

        // We do not need to calculate the data MD5 hash during upload if the
        // expected hash value was provided as the object's Content-MD5 header.
        boolean isLiveMD5HashingRequired =
            (object.getMetadata(S3Object.METADATA_HEADER_CONTENT_MD5) == null);
        String s3Endpoint = this.jets3tProperties.getStringProperty(
            "s3service.s3-endpoint", Constants.S3_DEFAULT_HOSTNAME);

        if (object.getDataInputStream() != null) {
            repeatableRequestEntity = new RepeatableRequestEntity(object.getKey(),
                object.getDataInputStream(), object.getContentType(), object.getContentLength(),
                this.jets3tProperties, isLiveMD5HashingRequired);

            putMethod.setRequestEntity(repeatableRequestEntity);
        }

        performRequest(putMethod, new int[] {200});

        // Consume response data and release connection.
        putMethod.releaseConnection();
        try {
            object.closeDataInputStream();
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to close data input stream for object '" + object.getKey() + "'", e);
            }
        }

        try {
            S3Object uploadedObject = ServiceUtils.buildObjectFromUrl(
                putMethod.getURI().getHost(), putMethod.getPath(), s3Endpoint);
            uploadedObject.setBucketName(uploadedObject.getBucketName());

            // Add all metadata returned by S3 to uploaded object.
            HashMap map = new HashMap();
            map.putAll(convertHeadersToMap(putMethod.getResponseHeaders()));
            uploadedObject.replaceAllMetadata(ServiceUtils.cleanRestMetadataMap(map));

            // Confirm that the data was not corrupted in transit by checking S3's calculated
            // hash value with the locally computed value. This is only necessary if the user
            // did not provide a Content-MD5 header with the original object.
            // Note that we can only confirm the data if we used a RepeatableRequestEntity to
            // upload it, if the user did not provide a content length with the original
            // object we are SOL.
            if (repeatableRequestEntity != null && isLiveMD5HashingRequired) {
                // Obtain locally-calculated MD5 hash from request entity.
                String hexMD5OfUploadedData = ServiceUtils.toHex(
                    repeatableRequestEntity.getMD5DigestOfData());
                verifyExpectedAndActualETagValues(hexMD5OfUploadedData, uploadedObject);
            }

            return uploadedObject;
        } catch (URIException e) {
            throw new S3ServiceException("Unable to lookup URI for object created with signed PUT", e);
        } catch (UnsupportedEncodingException e) {
            throw new S3ServiceException("Unable to determine name of object created with signed PUT", e);
        }
    }

    /**
     * Deletes an object using a pre-signed DELETE URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     * <p>
     * This operation does not required any S3 functionality as it merely
     * deletes the object by performing a standard HTTP DELETE using the signed URL.
     *
     * @param signedDeleteUrl
     * a signed DELETE URL.
     *
     * @throws S3ServiceException
     */
    public void deleteObjectWithSignedUrl(String signedDeleteUrl) throws S3ServiceException {
        DeleteMethod deleteMethod = new DeleteMethod(signedDeleteUrl);

        performRequest(deleteMethod, new int[] {204, 200});

        deleteMethod.releaseConnection();
    }

    /**
     * Gets an object using a pre-signed GET URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     * <p>
     * This operation does not required any S3 functionality as it merely
     * uploads the object by performing a standard HTTP GET using the signed URL.
     *
     * @param signedGetUrl
     * a signed GET URL.
     *
     * @return
     * the S3Object in S3 including all metadata and the object's data input stream.
     *
     * @throws S3ServiceException
     */
    public S3Object getObjectWithSignedUrl(String signedGetUrl) throws S3ServiceException {
        return getObjectWithSignedUrlImpl(signedGetUrl, false);
    }

    /**
     * Gets an object's details using a pre-signed HEAD URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     * <p>
     * This operation does not required any S3 functionality as it merely
     * uploads the object by performing a standard HTTP HEAD using the signed URL.
     *
     * @param signedHeadUrl
     * a signed HEAD URL.
     *
     * @return
     * the S3Object in S3 including all metadata, but without the object's data input stream.
     *
     * @throws S3ServiceException
     */
    public S3Object getObjectDetailsWithSignedUrl(String signedHeadUrl) throws S3ServiceException {
        return getObjectWithSignedUrlImpl(signedHeadUrl, true);
    }

    /**
     * Gets an object's ACL details using a pre-signed GET URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     *
     * @param signedAclUrl
     * a signed URL.
     *
     * @return
     * the AccessControlList settings of the object in S3.
     *
     * @throws S3ServiceException
     */
    public AccessControlList getObjectAclWithSignedUrl(String signedAclUrl)
        throws S3ServiceException
    {
        HttpMethodBase httpMethod = new GetMethod(signedAclUrl);

        HashMap requestParameters = new HashMap();
        requestParameters.put("acl","");

        performRequest(httpMethod, new int[] {200});
        return (new XmlResponsesSaxParser(this.jets3tProperties))
            .parseAccessControlListResponse(
                new HttpMethodReleaseInputStream(httpMethod)).getAccessControlList();
    }

    /**
     * Sets an object's ACL details using a pre-signed PUT URL generated for that object.
     * This method is an implementation of the interface {@link SignedUrlHandler}.
     *
     * @param signedAclUrl
     * a signed URL.
     * @param acl
     * the ACL settings to apply to the object represented by the signed URL.
     *
     * @throws S3ServiceException
     */
    public void putObjectAclWithSignedUrl(String signedAclUrl, AccessControlList acl) throws S3ServiceException {
        PutMethod putMethod = new PutMethod(signedAclUrl);

        if (acl != null) {
            if (AccessControlList.REST_CANNED_PRIVATE.equals(acl)) {
                putMethod.addRequestHeader(Constants.REST_HEADER_PREFIX + "acl", "private");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ.equals(acl)) {
                putMethod.addRequestHeader(Constants.REST_HEADER_PREFIX + "acl", "public-read");
            } else if (AccessControlList.REST_CANNED_PUBLIC_READ_WRITE.equals(acl)) {
                putMethod.addRequestHeader(Constants.REST_HEADER_PREFIX + "acl", "public-read-write");
            } else if (AccessControlList.REST_CANNED_AUTHENTICATED_READ.equals(acl)) {
                putMethod.addRequestHeader(Constants.REST_HEADER_PREFIX + "acl", "authenticated-read");
            } else {
                try {
                    String aclAsXml = acl.toXml();
                    putMethod.setRequestEntity(new StringRequestEntity(
                        aclAsXml, "text/xml", Constants.DEFAULT_ENCODING));
                } catch (UnsupportedEncodingException e) {
                    throw new S3ServiceException("Unable to encode ACL XML document", e);
                }

            }
        }

        performRequest(putMethod, new int[] {200});

        // Consume response data and release connection.
        putMethod.releaseConnection();
    }

    private S3Object getObjectWithSignedUrlImpl(String signedGetOrHeadUrl, boolean headOnly)
        throws S3ServiceException
    {
        String s3Endpoint = this.jets3tProperties.getStringProperty(
            "s3service.s3-endpoint", Constants.S3_DEFAULT_HOSTNAME);

        HttpMethodBase httpMethod = null;
        if (headOnly) {
            httpMethod = new HeadMethod(signedGetOrHeadUrl);
        } else {
            httpMethod = new GetMethod(signedGetOrHeadUrl);
        }

        performRequest(httpMethod, new int[] {200});

        HashMap map = new HashMap();
        map.putAll(convertHeadersToMap(httpMethod.getResponseHeaders()));

        S3Object responseObject = null;
        try {
            responseObject = ServiceUtils.buildObjectFromUrl(
                httpMethod.getURI().getHost(),
                httpMethod.getPath().substring(1),
                s3Endpoint);
        } catch (URIException e) {
            throw new S3ServiceException("Unable to lookup URI for object created with signed PUT", e);
        } catch (UnsupportedEncodingException e) {
            throw new S3ServiceException("Unable to determine name of object created with signed PUT", e);
        }

        responseObject.replaceAllMetadata(ServiceUtils.cleanRestMetadataMap(map));
        responseObject.setMetadataComplete(true); // Flag this object as having the complete metadata set.
        if (!headOnly) {
            HttpMethodReleaseInputStream releaseIS = new HttpMethodReleaseInputStream(httpMethod);
            responseObject.setDataInputStream(releaseIS);
        } else {
            // Release connection after HEAD (there's no response content)
            if (log.isDebugEnabled()) {
                log.debug("Releasing HttpMethod after HEAD");
            }
            httpMethod.releaseConnection();
        }

        return responseObject;
    }

    /**
     * Simple container object to store an HttpMethod object representing a request connection, and a
     * count of the byte size of the S3 object associated with the request.
     * <p>
     * This object is used when S3 objects are created to associate the connection and the actual size
     * of the object as reported back by S3.
     *
     * @author James Murty
     */
    public class HttpMethodAndByteCount {
        private HttpMethodBase httpMethod = null;
        private long byteCount = 0;

        public HttpMethodAndByteCount(HttpMethodBase httpMethod, long byteCount) {
            this.httpMethod = httpMethod;
            this.byteCount = byteCount;
        }

        public HttpMethodBase getHttpMethod() {
            return httpMethod;
        }

        public long getByteCount() {
            return byteCount;
        }
    }

}
