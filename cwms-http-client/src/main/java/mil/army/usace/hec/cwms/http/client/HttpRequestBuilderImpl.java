/*
 * MIT License
 *
 * Copyright (c) 2023 Hydrologic Engineering Center
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mil.army.usace.hec.cwms.http.client;

import mil.army.usace.hec.cwms.http.client.request.HttpPostRequest;
import mil.army.usace.hec.cwms.http.client.request.HttpRequestExecutor;
import mil.army.usace.hec.cwms.http.client.request.HttpRequestMediaType;
import mil.army.usace.hec.cwms.http.client.request.HttpRequestMethod;
import okhttp3.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import usace.metrics.noop.NoOpTimer;
import usace.metrics.services.Metrics;
import usace.metrics.services.Timer;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.Security;
import java.security.SignatureException;
import java.util.*;

import static java.util.stream.Collectors.toSet;
import static mil.army.usace.hec.cwms.http.client.Http2Util.isHttp2NativelySupported;

public class HttpRequestBuilderImpl implements HttpRequestBuilder {

    private final String endpoint;
    private final HttpUrl httpUrl;
    private final Map<String, String> queryParameters = new HashMap<>();
    private final Map<String, String> queryHeaders = new HashMap<>();
    private final ApiConnectionInfo apiConnectionInfo;
    private HttpRequestMethod method;
    private String body;
    private String mediaType;

    public HttpRequestBuilderImpl(ApiConnectionInfo apiConnectionInfo, String endpoint) throws IOException {
        Objects.requireNonNull(apiConnectionInfo, "API connection info must be defined");
        String apiRoot = apiConnectionInfo.getApiRoot();
        Objects.requireNonNull(apiRoot, "API root must be defined");
        HttpUrl url = HttpUrl.parse(apiRoot);
        if (url == null) {
            throw new ServerNotFoundException("Invalid HTTP URL: " + apiRoot, apiRoot);
        }
        this.httpUrl = url;
        this.endpoint = Objects.requireNonNull(endpoint, "Cannot process request against the API root endpoint");
        this.apiConnectionInfo = apiConnectionInfo;
    }

    public HttpRequestBuilderImpl(ApiConnectionInfo apiConnectionInfo) throws IOException {
        this(apiConnectionInfo, "");
    }

    @Override
    public final HttpRequestBuilderImpl addQueryParameter(String key, String value) {
        if (value == null) {
            queryParameters.remove(key);
        } else {
            queryParameters.put(key, value);
        }
        return this;
    }

    @Override
    public final HttpRequestBuilderImpl addQueryHeader(String key, String value) {
        if (value == null) {
            queryParameters.remove(key);
        } else {
            queryHeaders.put(key, value);
        }
        return this;
    }

    @Override
    public final HttpRequestBuilderImpl addEndpointInput(EndpointInput endpointInput) {
        endpointInput.addInputParameters(this);
        return this;
    }

    /**
     * Enables HTTP/2 protocol if running a Java 8 version before 251.
     *
     * @return HttpRequestBuilder
     */
    @Override
    public HttpRequestBuilder enableHttp2() {
        //if Java 8 less than minor version 251, then use BouncyCastle to allow for HTTP/2 requests
        if (!isHttp2NativelySupported()) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
        }
        return this;
    }

    @Override
    public final HttpPostRequest post() {
        this.method = HttpRequestMethod.POST;
        return new HttpPostRequestImpl();
    }

    @Override
    public final HttpPostRequest patch() {
        this.method = HttpRequestMethod.PATCH;
        return new HttpPostRequestImpl();
    }

    @Override
    public final HttpRequestMediaType delete() {
        this.method = HttpRequestMethod.DELETE;
        return new HttpRequiredMediaTypeImpl();
    }

    @Override
    public final HttpRequestMediaType get() {
        this.method = HttpRequestMethod.GET;
        return new HttpRequiredMediaTypeImpl();
    }

    protected OkHttpClient buildOkHttpClient() {
        return OkHttpClientFactory.buildOkHttpClient(apiConnectionInfo);
    }

    //Packaged scope for testing
    Request createRequest() throws IOException {
        HttpUrl resolve = httpUrl;
        if (!endpoint.isEmpty()) {
            resolve = httpUrl.newBuilder()
                .addPathSegments(endpoint)
                .build();
        }
        MediaType type = MediaType.parse(mediaType);
        if (type == null) {
            throw new IOException("Invalid Media Type: " + mediaType);
        }
        HttpUrl.Builder urlBuilder = resolve.newBuilder();
        queryParameters.forEach(urlBuilder::addQueryParameter);
        Request.Builder requestBuilder = new Request.Builder();
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(body, null);
        }
        requestBuilder.header("Content-Type", type.toString());
        requestBuilder.url(urlBuilder.build());
        requestBuilder.method(method.getName(), requestBody);
        queryHeaders.forEach(requestBuilder::addHeader);
        return requestBuilder.build();
    }


    //Packaged scope for testing
    HttpRequestBuilderImpl getCurrentInstance() {
        return this;
    }

    private class HttpPostRequestImpl implements HttpPostRequest {

        @Override
        public HttpRequestMediaType withBody(String postBody) {
            body = postBody;
            return new HttpRequiredMediaTypeImpl();
        }
    }

    private class HttpRequiredMediaTypeImpl implements HttpRequestMediaType {

        @Override
        public HttpRequestExecutor withMediaType(String type) {
            mediaType = type;
            return new HttpRequestExecutorImpl();
        }
    }

    class HttpRequestExecutorImpl implements HttpRequestExecutor {

        @Override
        public final HttpRequestResponse execute() throws IOException {
            HttpRequestResponse retVal = null;
            Request request = createRequest();
            CwmsHttpLoggingInterceptor.getInstance().logStackTraceForRequest(request);
            ResponseBody responseBody;
            try (Timer.Context timer = createTimer().start()) {
                OkHttpClient client = buildOkHttpClient();
                Response execute = client.newCall(request).execute();
                if (execute.isSuccessful()) {
                    responseBody = execute.body();
                    if (responseBody == null) {
                        throw new IOException("Error with request, body not returned for request: " + request);
                    }
                    Set<HttpCookie> cookies = client.cookieJar().loadForRequest(request.url())
                        .stream()
                        .map(OkHttpCookieWrapper::new)
                        .collect(toSet());
                    retVal = new HttpRequestResponse(responseBody, cookies);
                } else {
                    handleExecutionError(execute, request);
                }
            } catch (ConnectException | UnknownHostException | SocketTimeoutException connectException) {
                throw new ServerNotFoundException(connectException, request.url().toString());
            } catch (SSLHandshakeException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof SignatureException && cause.getMessage().contains("The action was cancelled by the user.")) {
                    throw new SslCanceledException(ex, request.url().toString());
                } else {
                    throw ex;
                }
            }
            return retVal;
        }

        private void handleExecutionError(Response execute, Request request) throws IOException {
            try (ResponseBody responseBody = execute.body()) {
                checkError(execute, request, responseBody);
            }
        }

        private void checkError(Response execute, Request request, ResponseBody responseBody) throws IOException {
            int code = execute.code();
            switch (code) {
                case HttpURLConnection.HTTP_NOT_FOUND:
                    throw new NoDataFoundException(execute, request, responseBody);
                case HttpURLConnection.HTTP_UNAUTHORIZED:
                    throw new UnauthorizedException(execute, request, responseBody);
                case HttpURLConnection.HTTP_CONFLICT:
                    throw new DataAlreadyExistsException(execute, request, responseBody);
                default:
                    throw new CwmsHttpResponseException(execute, request, responseBody);
            }
        }

        private Timer createTimer() {
            if (!CwmsHttpClientMetrics.isMetricsEnabled()) {
                return new NoOpTimer();
            }
            Metrics metrics = CwmsHttpClientMetrics.createMetrics(Objects.toString(httpUrl.resolve(endpoint)));
            Timer timer = metrics.createTimer();
            Properties metricsProperties = new Properties();
            metricsProperties.putAll(queryParameters);
            metricsProperties.putAll(queryHeaders);
            timer.setMetricProperties(metricsProperties);
            return timer;
        }

        HttpRequestBuilderImpl getInstance() {
            return getCurrentInstance();
        }
    }

}


