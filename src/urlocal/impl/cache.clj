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

(ns urlocal.impl.cache
  "This namespace is not part of the public API of urlocal and may change
  without notice."
  (:require [clojure.string        :as s]
            [clojure.java.io       :as io]
            [clojure.tools.logging :as log]
            [clojure.edn           :as edn]
            [urlocal.impl.xdg      :as xdg]))

(def cache-dir-a                 (atom (str xdg/cache-home "urlocal")))
(def cache-check-interval-secs-a (atom 86400))  ; 86400 seconds = 24 hours

(defn base64-encode
  "Returns a BASE64 encoded representation (a String) of the UTF-8 String
  representation of x, or nil if x is nil"
  [x]
  (when x
    (let [s (str x)]
      (.encodeToString (java.util.Base64/getEncoder) (.getBytes s java.nio.charset.StandardCharsets/UTF_8)))))

(defn cache-key->cache-file
  "Returns a File for the given cache-key (a String), optionally with the given
  extension.  Return nil if cache-key is blank."
  ([cache-key] (cache-key->cache-file cache-key nil))
  ([cache-key extension]
    (when-not (s/blank? cache-key)
      (io/file (str @cache-dir-a java.io.File/separator cache-key extension)))))

(defn url->content-file
  "Returns a content file for the given url, or nil if the url is nil."
  ^java.io.File [url]
  (when url
    (let [cache-key (base64-encode url)]
      (cache-key->cache-file cache-key ".content"))))

(defn url->metadata-file
  "Returns a metadata file for the given url, or nil if the url is nil."
  ^java.io.File [url]
  (when url
    (let [cache-key (base64-encode url)]
      (cache-key->cache-file cache-key ".metadata.edn"))))

(defn write-metadata-file!
  "Writes the metadata m to the file f, overwriting it if it already exists."
  [f m]
  (when (and f m)
    (spit f (pr-str m))))

(defn write-metadata!
  "Writes out a metadata file for the given open connection.

  Note: does nothing if the connection did not return an ETag - this ensures
  that future requests to the same URL will always be treated as a cache miss."
  [^java.net.HttpURLConnection conn]
  (let [url  (.getURL conn)
        now  (java.util.Date.)
        etag (.getHeaderField conn "ETag")]
    (when-not (s/blank? etag)
      (write-metadata-file! (url->metadata-file url)
                            {:url             (str url)
                             :etag            etag
                             :downloaded-at   now
                             :last-checked-at now}))))

(defn remove-cache-entry!
  "Removes the cache entry for a single URL, if it exists. Returns nil.

  Throws on IO errors."
  [url]
  (let [content-file  (url->content-file url)
        metadata-file (url->metadata-file url)]
    (when (and content-file  (.exists content-file))  (.delete content-file))
    (when (and metadata-file (.exists metadata-file)) (.delete metadata-file)))
  nil)

(defmulti seconds-since
  "Returns how many seconds have passed since inst (a Date or Temporal), or
  Long/MAX_VALUE if inst is nil."
  {:arglists '([inst])}
  type)

(defmethod seconds-since nil
  [_]
  Long/MAX_VALUE)

(defmethod seconds-since java.time.Instant
  [^java.time.Instant inst]
  (.between (java.time.temporal.ChronoUnit/SECONDS) inst (java.time.Instant/now)))

(defmethod seconds-since java.util.Date
  [^java.util.Date d]
  (seconds-since (.toInstant d)))

#_{:clj-kondo/ignore [:unused-binding {:exclude-destructured-keys-in-fn-args true}]}
(defn http-get
  "Perform an HTTP GET request for the given URL, using the given options,
  returning an HTTPUrlConnection object.

  Throws on IO errors."
  (^java.net.HttpURLConnection [^java.net.URL url] (http-get url nil))
  (^java.net.HttpURLConnection [^java.net.URL url
                                {:keys [connect-timeout read-timeout follow-redirects? request-headers]
                                 :or   {connect-timeout   1000
                                        read-timeout      1000
                                        follow-redirects? false
                                        request-headers   {"User-Agent" "com.github.pmonks/urlocal"}}}]
   (when url
     (let [conn (doto ^java.net.HttpURLConnection  (.openConnection url)
                      (.setRequestMethod           "GET")
                      (.setConnectTimeout          connect-timeout)
                      (.setReadTimeout             read-timeout)
                      (.setInstanceFollowRedirects false))]  ; Note: we handle redirects ourselves, to ensure cache coherence
       (run! #(.setRequestProperty conn (key %) (val %)) (merge {"User-Agent" "com.github.pmonks/urlocal"} request-headers))  ; Note: ensure there's always a User-Agent header
       (.connect conn)
       conn))))

(defn- get-retry-after-header
  "Gets the HTTP Retry-After header value from conn (which can be either an
  integer (# of seconds) or an HTTP date (as per RFC-2616)), returning a
  positive integer number of seconds to wait before retrying, or nil if the
  header doesn't exist, the value is invalid (malformed, negative, etc.)."
  [^java.net.HttpURLConnection conn]
  (when (.getHeaderField conn "Retry-After")
    (let [retry-after-epoch (.getHeaderFieldDate conn "Retry-After " -1)]
      (if (neg? retry-after-epoch)
        ; Not a date, so try an integer
        (let [retry-after-seconds (.getHeaderFieldLong conn "Retry-After" -1)]
          (if (neg? retry-after-seconds)
            nil    ; Not an integer either, so give up
            retry-after-seconds))
        (let [now (.getTime       (java.util.Date.))
              retry-after-seconds (Math/round (double (/ (- now retry-after-epoch) 1000)))]
          (if (neg? retry-after-seconds)
            nil
            retry-after-seconds))))))

(defmulti cache-miss!
  "Handles a cache miss, by caching content locally and capturing metadata from
  the response for the purposes of cache management.

  source may be:
  * a URL, in which case an HTTP GET request is made
  * a HttpURLConnection, in which case it is assumed to already be connected

  Throws on IO errors or unexpected HTTP status code responses."
  {:arglists '([source opts] [source already-redirected? opts])}
  (fn [source & _] (type source)))

(defmethod cache-miss! java.net.HttpURLConnection
  ([^java.net.HttpURLConnection conn opts] (cache-miss! conn false false opts))
  ([^java.net.HttpURLConnection conn
                                already-redirected?
                                already-retried?
                                {:keys [follow-redirects? retry-when-throttled? max-retry-after]
                                 :or   {follow-redirects?     false
                                        retry-when-throttled? false
                                        max-retry-after       10}
                                 :as   opts}]
   (let [url           (.getURL           conn)
         content-file  (url->content-file url)
         response-code (.getResponseCode  conn)]
     (cond
       ; Normal response (200)
       (= response-code java.net.HttpURLConnection/HTTP_OK)
         (let [is (.getInputStream conn)
               os (io/output-stream (io/file content-file))]
           (io/copy is os)
           (write-metadata! conn))

       ; Redirect (301/302)
       (and follow-redirects?
            (not already-redirected?)
            (or (= response-code java.net.HttpURLConnection/HTTP_MOVED_PERM)
                (= response-code java.net.HttpURLConnection/HTTP_MOVED_TEMP)))
         (let [new-url (io/as-url (.getHeaderField conn "Location"))]
           (remove-cache-entry! url)  ; Remove any cache entries for the original URL, since it's no longer serving content
           (cache-miss! (http-get new-url opts) true already-retried? opts))

       ; Throttled (429)
       (and retry-when-throttled?
            (not already-retried?)
            (= response-code 429))   ; Note: java.net.HttpURLConnection doesn't have a symbolic constant for status code 429
         (if-let [retry-after (get-retry-after-header conn)]
           (if (<= retry-after max-retry-after)
             (let [sleep-ms (long (* 1000 retry-after))]
               (log/debugf "Request to %s throttled (429), sleeping %ds then retrying..." (str url) retry-after)
               (Thread/sleep sleep-ms)
               (cache-miss! (http-get url opts) already-redirected? true opts))
             (throw (ex-info (str "Request to " url " throttled (429), but requested retry (" retry-after "s) was longer than maximum allowed (" max-retry-after "s).") (into {} (.getHeaderFields conn)))))
           (throw (ex-info (str "Request to " url " throttled (429), but Retry-After response header was missing or invalid.") (into {} (.getHeaderFields conn)))))

       :else
         (throw (ex-info (str "Unexpected HTTP response from " url ": " response-code) (into {} (.getHeaderFields conn))))))
   nil))

(defmethod cache-miss! java.net.URL
  [^java.net.URL url opts]
  (cache-miss! (http-get url opts) opts))

(defn cache-hit!
  "Handles a cache hit, by updating the :last-checked-at metadata.  Assumes that
  the cache is already populated with the given URL.

  Throws on IO errors."
  [url metadata-file metadata]
  (log/debugf "Cache hit - cached copy of %s is up to date." (str url))
  (write-metadata-file! metadata-file (assoc metadata :last-checked-at (java.util.Date.))))

(defn check-cache!
  "Handles a potential cache hit, by determining whether the cached content
  needs to be checked for staleness via an ETag request, or whether it can
  simply be served directly.  Assumes that the cache is already populated with
  the given URL.

  Throws on IO errors or unexpected HTTP status code responses."
  [^java.net.URL url {:keys [request-headers return-cached-content-on-exception?] :as opts}]
  (let [metadata-file            (url->metadata-file url)
        metadata                 (edn/read-string (slurp metadata-file))
        last-checked             (:last-checked-at metadata)
        seconds-since-last-check (seconds-since last-checked)]
    (if (> seconds-since-last-check @cache-check-interval-secs-a)
      (try
        (let [conn (http-get url (assoc opts :request-headers (assoc request-headers "If-None-Match" (:etag metadata))))]
          (log/debugf "Cache check interval exceeded; checking cached copy of %s for staleness..." (str url))

          (if (= (.getResponseCode conn) java.net.HttpURLConnection/HTTP_NOT_MODIFIED)
            (cache-hit! url metadata-file metadata)
            (cache-miss! conn opts)))  ; Handle a stale cache entry as a cache miss
        (catch Exception e
          (if return-cached-content-on-exception?
            (log/warnf e "Unexpected exception while checking %s for staleness. Potentially stale cached content will be used." (str url))
            (throw e))))
      (log/debug "Cache hit - within cache check interval; skipped staleness check for cached copy of" (str url))))
  nil)

(defn prep-cache!
  "Ensures the cache is populated for the given url."
  [^java.net.URL url opts]
  (when url
    (when-not (.exists (io/file @cache-dir-a)) (io/make-parents (io/file @cache-dir-a "dummy.txt")))
    (let [cached-content-file  (url->content-file url)
          cached-metadata-file (url->metadata-file url)]
      (if (and cached-content-file
               (.exists cached-content-file)
               cached-metadata-file
               (.exists cached-metadata-file))
        (check-cache! url opts)
        (cache-miss! url opts)))))
