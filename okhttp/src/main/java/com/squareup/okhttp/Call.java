/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.http.HttpEngine;
import com.squareup.okhttp.internal.http.RequestException;
import com.squareup.okhttp.internal.http.RouteException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.logging.Level;

import static com.squareup.okhttp.internal.Internal.logger;
import static com.squareup.okhttp.internal.http.HttpEngine.MAX_FOLLOW_UPS;

/**
 * 一个Call是一个已经准备好执行的Request，yigeCall可以被取消。因为该类表示单个请求/响应对，所以，只能执行一次。
 * A call is a request that has been prepared for execution. A call can be
 * canceled. As this object represents a single request/response pair (stream),
 * it cannot be executed twice.
 */
public class Call {
  private final OkHttpClient client;

  // Guarded by this.
  private boolean executed;
  volatile boolean canceled;

  /** The application's original request unadulterated by redirects or auth headers. */
  /** 从重定向或者Auth认证Header过来的纯正原始请求 */
  Request originalRequest;
  HttpEngine engine;

  protected Call(OkHttpClient client, Request originalRequest) {
    // Copy the client. Otherwise changes (socket factory, redirect policy,
    // etc.) may incorrectly be reflected in the request when it is executed.
    this.client = client.copyWithDefaults();
    this.originalRequest = originalRequest;
  }

  /**
   * 立即开始请求,并阻塞，直到响应可以被处理或者是以错误结束.
   * Invokes the request immediately, and blocks until the response can be
   * processed or is in error.
   *
   *
   * <p>Caller可以通过response的body方法读取response的body，为了便于连接回收，Caller需要关闭reponse的body。
   *
   * <p>The caller may read the response body with the response's
   * {@link Response#body} method.  To facilitate connection recycling, callers
   * should always {@link ResponseBody#close() close the response body}.
   *
   *
   * <p>需要注意的是传送层成功（接收一个HTTP响应代码，标头和身体）并不一定表示应用层的成功：
   * 响应可能仍表示相同的404或500不愉快的HTTP响应代码。
   * <p>Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer success:
   * {@code response} may still indicate an unhappy HTTP response code like 404
   * or 500.
   *
   * @throws IOException if the request could not be executed due to
   *     cancellation, a connectivity problem or timeout. Because networks can
   *     fail during an exchange, it is possible that the remote server
   *     accepted the request before the failure.
   *
   *     如果因为该请求因为被取消、连接失败、超时等原因而不能被执行，就抛出IOException。
   *     因为网络可能在任何一次数据交换时失败，所以远程服务可能已经在客户端得到失败结果前接受了这次请求。
   *
   * @throws IllegalStateException when the call has already been executed.如果这个Call已经被执行过了，就抛出IllegalStateException
   */
  public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    try {
      client.getDispatcher().executed(this);
      Response result = getResponseWithInterceptorChain(false);
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.getDispatcher().finished(this);
    }
  }

  Object tag() {
    return originalRequest.tag();
  }

  /**
   * Schedules the request to be executed at some point in the future.
   * 安排请求在未来的某个时候被执行。
   *
   * <p>The {@link OkHttpClient#getDispatcher dispatcher} defines when the
   * request will run: usually immediately unless there are several other
   * requests currently being executed.
   *
   * <p>{@link OkHttpClient#getDispatcher dispatcher} 决定该请求将在合适被执行。
   * 如果没有其他几个请求正在被执行，通常会立即被执行。
   *
   * <p>This client will later call back {@code responseCallback} with either
   * an HTTP response or a failure exception. If you {@link #cancel} a request
   * before it completes the callback will not be invoked.
   *
   * <p>客户端稍后会得到一个HTTP请求成功的response回调，或者是一个失败的异常。如果你在完成之前取消了该请求，
   * 那么回调不会被调用。
   *
   * @throws IllegalStateException when the call has already been executed.
   * 如果这个Call已经被执行过了，就抛出IllegalStateException
   */
  public void enqueue(Callback responseCallback) {
    enqueue(responseCallback, false);
  }

  void enqueue(Callback responseCallback, boolean forWebSocket) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    client.getDispatcher().enqueue(new AsyncCall(responseCallback, forWebSocket));
  }

  /**
   * 尝试取消请求。已经完成的请求无法被取消掉。
   * Cancels the request, if possible. Requests that are already complete
   * cannot be canceled.
   */
  public void cancel() {
    canceled = true;
    if (engine != null) engine.disconnect();
  }

  public boolean isCanceled() {
    return canceled;
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;
    private final boolean forWebSocket;

    private AsyncCall(Callback responseCallback, boolean forWebSocket) {
      super("OkHttp %s", originalRequest.urlString());
      this.responseCallback = responseCallback;
      this.forWebSocket = forWebSocket;
    }

    String host() {
      return originalRequest.httpUrl().host();
    }

    Request request() {
      return originalRequest;
    }

    Object tag() {
      return originalRequest.tag();
    }

    void cancel() {
      Call.this.cancel();
    }

    Call get() {
      return Call.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain(forWebSocket);
        if (canceled) {
          signalledCallback = true;
          responseCallback.onFailure(originalRequest, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          logger.log(Level.INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          Request request = engine == null ? originalRequest : engine.getRequest();
          responseCallback.onFailure(request, e);
        }
      } finally {
        client.getDispatcher().finished(this);
      }
    }
  }

  /**
   * 返回一个用来描述该call的字符串。因为有可能泄露敏感信息，这里面不包含完整的URL。
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = canceled ? "canceled call" : "call";
    HttpUrl redactedUrl = originalRequest.httpUrl().resolve("/...");
    return string + " to " + redactedUrl;
  }

  private Response getResponseWithInterceptorChain(boolean forWebSocket) throws IOException {
    Interceptor.Chain chain = new ApplicationInterceptorChain(0, originalRequest, forWebSocket);
    return chain.proceed(originalRequest);
  }

  class ApplicationInterceptorChain implements Interceptor.Chain {
    private final int index;
    private final Request request;
    private final boolean forWebSocket;

    ApplicationInterceptorChain(int index, Request request, boolean forWebSocket) {
      this.index = index;
      this.request = request;
      this.forWebSocket = forWebSocket;
    }

    @Override public Connection connection() {
      return null;
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      // If there's another interceptor in the chain, call that.
      //如果存在其他拦截器，调用他
      //先判断是否每个拦截器都有对应的处理，没有的话先继续新建ApplicationInterceptorChain ，并执行当前拦截器的intercept方法
      //就会去判断是否有拦截器有的话先执行拦截器里的intercept，而在intercept里一般会进行一些自定义操作并且调用procced去判断是否要继续执行拦截器操作还是直接去获取网络请求
      if (index < client.interceptors().size()) {
        Interceptor.Chain chain = new ApplicationInterceptorChain(index + 1, request, forWebSocket);
        Interceptor interceptor = client.interceptors().get(index);
        Response interceptedResponse = interceptor.intercept(chain);

        if (interceptedResponse == null) {
          throw new NullPointerException("application interceptor " + interceptor
              + " returned null");
        }

        return interceptedResponse;
      }

      //没有更多的拦截器了，执行HTTP请求
      // No more interceptors. Do HTTP.
      return getResponse(request, forWebSocket);
    }
  }

  /**
   *执行网络请求得到Response。如果该请求被取消了，可能会返回null。
   * Performs the request and returns the response. May return null if this
   * call was canceled.
   */
  Response getResponse(Request request, boolean forWebSocket) throws IOException {
    // Copy body metadata to the appropriate request headers.
    // 把body的一些的元数据复制到相应的请求头。
    RequestBody body = request.body();
    if (body != null) {
      //如果是post方式（Body不为空），处理一些头部信息
      Request.Builder requestBuilder = request.newBuilder();

      MediaType contentType = body.contentType();
      if (contentType != null) {
        requestBuilder.header("Content-Type", contentType.toString());
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }

      request = requestBuilder.build();
    }

    // Create the initial HTTP engine. Retries and redirects need new engine for each attempt.
    //新建HttpEngine，用于进行发送请求和读取答复的细节处理，重试和重定向的每次尝试，都需要新的引擎。
    engine = new HttpEngine(client, request, false, false, forWebSocket, null, null, null, null);

    int followUpCount = 0;
    while (true) {
      if (canceled) {
        engine.releaseConnection();
        throw new IOException("Canceled");
      }

      try {
        engine.sendRequest();
        engine.readResponse();
      } catch (RequestException e) {
        // The attempt to interpret the request failed. Give up.
        throw e.getCause();
      } catch (RouteException e) {
        // The attempt to connect via a route failed. The request will not have been sent.
        HttpEngine retryEngine = engine.recover(e);
        if (retryEngine != null) {
          engine = retryEngine;
          continue;
        }
        // Give up; recovery is not possible.
        throw e.getLastConnectException();
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
        HttpEngine retryEngine = engine.recover(e, null);
        if (retryEngine != null) {
          engine = retryEngine;
          continue;
        }

        // Give up; recovery is not possible.
        throw e;
      }

      Response response = engine.getResponse();
      //得到该请求对应的后续请求，比如重定向之类的
      Request followUp = engine.followUpRequest();

      if (followUp == null) {
        if (!forWebSocket) {
          engine.releaseConnection();
        }
        return response;
      }

      if (++followUpCount > MAX_FOLLOW_UPS) {
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      if (!engine.sameConnection(followUp.httpUrl())) {
        engine.releaseConnection();
      }

      Connection connection = engine.close();
      request = followUp;
      engine = new HttpEngine(client, request, false, false, forWebSocket, connection, null, null,
          response);
    }
  }
}
