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

import java.io.IOException;

/**
 * //拦截器可以用来转换，重试，重写请求的机制。
 * 典型地，拦截器将被用来添加、移除、或者转化Request和Response的Headers信息
 *
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * requests coming back in. Typically interceptors will be used to add, remove, or transform headers
 * on the request or response.
 */
public interface Interceptor {
  Response intercept(Chain chain) throws IOException;

  interface Chain {
    Request request();
    Response proceed(Request request) throws IOException;
    Connection connection();
  }
}
