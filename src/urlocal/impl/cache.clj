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
  "Writes out a metadata file for the given url, using the open connection for
  that url.

  Note: does nothing if the connection did not return an ETag - this ensures
  that future requests to the same URL will always be treated as a cache miss."
  [url ^java.net.HttpURLConnection conn]
  (let [now  (java.util.Date.)
        etag (.getHeaderField conn "ETag")]
    (when-not (s/blank? etag)
      (write-metadata-file! (url->metadata-file url)
                            {:url             (str url)
                             :etag            etag
                             :downloaded-at   now
                             :last-checked-at now}))))

(defmulti seconds-since
  "Returns how many seconds have passed since inst (a Date or Temporal), or nil
  if inst is nil."
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

(defn http-get
  "Perform an HTTP get request for the given URL, using the given options,
  returning an HTTPUrlConnection object.

  Throws on IO errors."
  (^java.net.HttpURLConnection [^java.net.URL url] (http-get url nil))
  (^java.net.HttpURLConnection [^java.net.URL url
                                {:keys [connect-timeout read-timeout follow-redirects? authenticator request-headers]
                                 :or   {connect-timeout   1000
                                        read-timeout      1000
                                        follow-redirects? false
                                        authenticator     nil
                                        request-headers   {"User-Agent" "com.github.pmonks/urlocal"}}}]
   (when url
     (let [conn (doto ^java.net.HttpURLConnection  (.openConnection url)
                      (.setRequestMethod           "GET")
                      (.setConnectTimeout          connect-timeout)
                      (.setReadTimeout             read-timeout)
                      (.setInstanceFollowRedirects follow-redirects?))]
       (when authenticator (.setAuthenticator conn authenticator))
       (run! #(.setRequestProperty conn (key %) (val %)) request-headers)
       (.connect conn)
       conn))))

(defn cache-miss!
  "Handles a cache miss, by downloading the content for the given url and
  caching it locally, and also capturing metadata from the response for the
  purposes of cache management in the future.

  Throws on IO errors."
  [url opts]
  (let [content-file (url->content-file url)
        conn         (http-get url opts)]
    (log/debug (str "Cache miss for " url " - downloading..."))

    (if (= (.getResponseCode conn) java.net.HttpURLConnection/HTTP_OK)
      (do
        (io/copy (.getInputStream conn) (io/output-stream (io/file content-file)))
        (write-metadata! url conn))
      (throw (ex-info (str "Unexpected HTTP response from " url ": " (.getResponseCode conn)) {}))))
  nil)

(defn check-cache!
  "Handles a potential cache hit, by determining whether the cached content
  needs to be checked for staleness via an ETag request, or whether it can
  simply be served directly.

  Throws on IO errors."
  [^java.net.URL url {:keys [request-headers] :as opts}]
  (let [metadata-file            (url->metadata-file url)
        metadata                 (edn/read-string (slurp metadata-file))
        last-checked             (:last-checked-at metadata)
        seconds-since-last-check (seconds-since last-checked)]
    (if (> seconds-since-last-check @cache-check-interval-secs-a)
      (let [conn (http-get url (assoc opts :request-headers (assoc request-headers "If-None-Match" (:etag metadata))))]
        (log/debug (str "Cache check interval interval exceeded; checking cached copy of " url " for staleness..."))

        (if (= (.getResponseCode conn) java.net.HttpURLConnection/HTTP_NOT_MODIFIED)
          (do
            (log/debug (str "Cache hit - cached copy of " url " is not stale."))
            (write-metadata-file! metadata-file (assoc metadata :last-checked-at (java.util.Date.))))
          ; Handle a stale cache entry as a cache miss
          (cache-miss! url opts)))
      (log/debug (str "Cache hit - within cache check interval; skipped staleness check for cached copy of " url))))
  nil)

(defn prep-cache!
  "Ensures the cache is populated for the given url."
  [^java.net.URL url opts]
  (when url
    (when-not (.exists (io/file @cache-dir-a)) (io/make-parents (io/file @cache-dir-a "dummy.txt")))
    (let [cached-content-file  (url->content-file url)
          cached-metadata-file (url->metadata-file url)]
      (if (and cached-content-file
               cached-metadata-file
               (.exists cached-content-file)
               (.exists cached-metadata-file))
        (check-cache! url opts)
        (cache-miss! url opts)))))
