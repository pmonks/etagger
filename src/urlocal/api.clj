;
; Copyright © 2023 Peter Monks
;
; Licensed under the Apache License Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; SPDX-License-Identifier: Apache-2.0
;

(ns urlocal.api
  "The public API of the urlocal library."
  (:require [clojure.string     :as s]
            [clojure.java.io    :as io]
            [urlocal.impl.xdg   :as xdg]
            [urlocal.impl.cache :as uic]))

#_{:clj-kondo/ignore [:unused-binding {:exclude-destructured-keys-in-fn-args true}]}
(defn input-stream
  "Retrieves the content from `url` and caches it, returning an `InputStream`
  for the content.  `url` may be a `String`, `java.net.URL` or `java.net.URI`.
  Returns `nil` if `url` is `nil` or unsupported (i.e. is not an http(s) URL).

  `opts` provides these tunables, all of them optional:

  * `:connect-timeout` (`int`, default `1000`): the maximum number of
    milliseconds to wait when establishing the socket connection
  * `:read-timeout` (`int`, default `1000`): the maximum number of milliseconds
    to wait when reading content from the socket connection
  * `:follows-redirects?` (`boolean`, default `false`): whether to follow a
    single redirect (HTTP status codes 301, 302) if the server issues one (more
    than one redirect will throw an exception)
  * `:retry-when-throttled?` (`boolean`, default `false`): whether to
    automatically handle throttled HTTP requests (HTTP status code 429), by
    sleeping as requested by the `Retry-After` HTTP response header, then
    retrying the request
  * `:max-retry-after` (`int`, default `10`): the maximum number of seconds to
    sleep when waiting to retry a throttled request
  * `:request-headers` (a `Map` with `String` keys and values): a map of request
    headers to send along with the request
  * `return-cached-content-on-exception?` (`boolean`, default `true`): whether
    (potentially stale) cached content should be returned if it's available, and
    an exception occurs while checking for staleness

  Throws on IO errors."
  ([url] (input-stream url nil))
  ([url {:keys [connect-timeout read-timeout follow-redirects? retry-when-throttled? max-retry-after request-headers return-cached-content-on-exception?]
         :or   {connect-timeout                     1000
                read-timeout                        1000
                follow-redirects?                   false
                retry-when-throttled?               false
                max-retry-after                     10
                request-headers                     {"User-Agent" "com.github.pmonks/urlocal"}
                return-cached-content-on-exception? true}
         :as   opts}]
   (when-let [u (io/as-url url)]
     (when (s/starts-with? (s/lower-case (.getProtocol u)) "http")
       (uic/prep-cache! u opts)
       (io/input-stream (uic/url->content-file u))))))


(defn cache-dir
  "Returns the current cache directory as a `java.io.File`."
  []
  (io/file @uic/cache-dir-a))

(defn cache-name
  "Returns the current name of the cache as a `String`."
  []
  (.getName (cache-dir)))

(defn set-cache-name!
  "Sets the name of the cache to `n` (which ends up being part of the cache
  directory's name), and returns `nil`.  Default name is `urlocal`.

  Notes:

  * `n` must not be blank.
  * the new cache directory may not be empty if it exists and was previously
    populated.
  * setting a new name after a previously named cache has already been
    populated will 'orphan' the prior cache. To avoid this, you should call
    `reset-cache!` prior to setting a new name.

  Throws on IO errors."
  [^String n]
  (when-not (s/blank? n)
    (let [cache-dir (str xdg/cache-home n)]
      (io/make-parents (io/file (str cache-dir java.io.File/separator "dummy.txt")))  ; Make the cache dir first, so that any IO errors get thrown before we swap the atom
      (swap! uic/cache-dir-a (constantly cache-dir))))
  nil)

(defn reset-cache!
  "Resets (i.e. deletes) the local cache, returning `nil`.

  Throws on IO errors."
  []
  (let [cache-dir (io/file @uic/cache-dir-a)]
    (when (and (.exists cache-dir)
               (.isDirectory cache-dir))
      (run! #(.delete ^java.io.File %) (reverse (file-seq cache-dir)))))
  nil)

(defn remove-cache-entry!
  "Removes the cache entry for the given `url`, if it exists. Returns `nil`.

  Throws on IO errors."
  [url]
  (uic/remove-cache-entry! url)
  nil)

(defn cache-check-interval-secs
  "Returns the current cache check interval, in seconds."
  []
  @uic/cache-check-interval-secs-a)

(defn set-cache-check-interval-secs!
  "Sets the cache check interval, in seconds.  Default is `86400` (24 hours).
  Returns `nil`."
  [^Long cache-check-interval-secs]
  (when cache-check-interval-secs
    (swap! uic/cache-check-interval-secs-a (constantly cache-check-interval-secs)))
  nil)
