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
  (:require [clojure.string        :as s]
            [clojure.java.io       :as io]
            [clojure.tools.logging :as log]
            [clojure.edn           :as edn]))

; Note: always ends with a path separator
(def ^:private cache-home (let [xdg-cache-home (System/getenv "XDG_CACHE_HOME")]
                            (if (s/blank? xdg-cache-home)
                              (str (System/getProperty "user.home") java.io.File/separator ".cache" java.io.File/separator)
                              (if (s/ends-with? xdg-cache-home java.io.File/separator)
                                xdg-cache-home
                                (str xdg-cache-home java.io.File/separator)))))

(def ^:private cache-dir-a                 (atom (str cache-home "urlocal")))
(def ^:private cache-check-interval-secs-a (atom 86400))  ; 86400 seconds = 24 hours

(defn- base64-encode
  "Returns a BASE64 encoded representation (a String) of the UTF-8 String
  representation of x, or nil if x is nil"
  [x]
  (when x
    (let [s (str x)]
      (.encodeToString (java.util.Base64/getEncoder) (.getBytes s java.nio.charset.StandardCharsets/UTF_8)))))

(defn- cache-key->cache-file
  "Returns a File for the given cache-key (a String), optionally with the given
  extension.  Return nil if cache-key is blank."
  ([cache-key] (cache-key->cache-file cache-key nil))
  ([cache-key extension]
    (when-not (s/blank? cache-key)
      (io/file (str @cache-dir-a java.io.File/separator cache-key extension)))))

(defn- url->content-file
  "Returns a content file for the given url, or nil if the url is nil."
  ^java.io.File [url]
  (when url
    (let [cache-key (base64-encode url)]
      (cache-key->cache-file cache-key ".content"))))

(defn- url->metadata-file
  "Returns a metadata file for the given url, or nil if the url is nil."
  ^java.io.File [url]
  (when url
    (let [cache-key (base64-encode url)]
      (cache-key->cache-file cache-key ".metadata.edn"))))

(defn- write-metadata-file!
  "Writes the metadata m to the file f, overwriting it if it already exists."
  [f m]
  (when (and f m)
    (spit f (pr-str m))))

(defn- write-metadata!
  "Writes out a metadata file for the given url, using the open connection for
  that url.

  Note: does nothing if the connection did not return an ETag - this ensures
  that future requests to the same URL will always be treated as a cache miss."
  [url ^java.net.HttpURLConnection conn]
  (let [now  (java.time.Instant/now)
        etag (.getHeaderField conn "ETag")]
    (when-not (s/blank? etag)
      (write-metadata-file! (url->metadata-file url)
                            {:url             url
                             :etag            etag
                             :downloaded-at   now
                             :last-checked-at now}))))

(defmulti ^:private seconds-since
  "Returns how many seconds have passed since inst (a Date or Temporal), or nil
  if inst is nil."
  {:arglists '([inst])}
  type)

(defmethod ^:private seconds-since nil
  [_])

(defmethod ^:private seconds-since java.time.Instant
  [^java.time.Instant inst]
  (.between (java.time.temporal.ChronoUnit/SECONDS) inst (java.time.Instant/now)))

(defmethod ^:private seconds-since java.util.Date
  [^java.util.Date d]
  (seconds-since (.toInstant d)))

(defn- http-get
  "Perform an HTTP get request for the given URL, using the given options,
  returning an HTTPUrlConnection object.

  Throws on IO errors."
  ^java.net.HttpURLConnection [^java.net.URL url {:keys [connect-timeout read-timeout follow-redirects? authenticator request-headers]}]
  (let [conn (doto ^java.net.HttpURLConnection  (.openConnection url)
                   (.setRequestMethod           "GET")
                   (.setConnectTimeout          connect-timeout)
                   (.setReadTimeout             read-timeout)
                   (.setInstanceFollowRedirects follow-redirects?))]
    (when authenticator (.setAuthenticator conn authenticator))
    (run! #(.setRequestProperty conn (key %) (val %)) request-headers)
    conn))

(defn- cache-miss!
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

(defn- check-cache!
  "Handles a potential cache hit, by determining whether the cached content
  needs to be checked for staleness via an ETag request, or whether it can
  simply be served directly.

  Throws on IO errors."
  [^java.net.URL url {:keys [request-headers] :as opts}]
  (let [metadata-file (url->metadata-file url)
        metadata      (edn/read-string (slurp metadata-file))
        last-checked  (:last-checked-at metadata)]
    (if (or (nil? last-checked)
            (> (seconds-since last-checked) @cache-check-interval-secs-a))
      (let [conn (http-get url (assoc opts :request-headers (assoc request-headers "If-None-Match" (:etag metadata))))]
        (log/debug (str "Cache check interval interval exceeded; checking cached version of " url " for staleness..."))

        (if (= (.getResponseCode conn) java.net.HttpURLConnection/HTTP_NOT_MODIFIED)
          (do
            (log/debug (str "Cache hit for " url))
            (write-metadata-file! metadata-file (assoc metadata :last-checked-at (java.time.Instant/now))))
          ; Handle a stale cache entry as a cache miss
          (cache-miss! url opts)))
      (log/debug (str "Within cache check interval; skipping staleness check for cached version of " url))))
  nil)

#_{:clj-kondo/ignore [:unused-binding {:exclude-destructured-keys-in-fn-args true}]}
(defn input-stream
  "Returns an InputStream for potentially cached content retrieved from url (a
  String, java.net.URL or java.net.URI), or nil if url is nil or unsupported
  (i.e. is not an http(s) URL).

  Throws on IO errors."
  ([url] (input-stream url nil))
  ([url {:keys [connect-timeout read-timeout follow-redirects? authenticator request-headers]
         :or   {connect-timeout   1000
                read-timeout      1000
                follow-redirects? false
                authenticator     nil
                request-headers   {"User-Agent" "com.github.pmonks/urlocal"}}
         :as   opts}]
   (when-let [u (io/as-url url)]
     (when (s/starts-with? (s/lower-case (.getProtocol u)) "http")
       (let [cached-content-file  (url->content-file u)
             cached-metadata-file (url->metadata-file u)]
         (if (and (.exists cached-content-file)
                  (.exists cached-metadata-file))
           (check-cache! u opts)
           (cache-miss! u opts))
         ; At this point we know the cached content file exists
         (io/input-stream cached-content-file))))))

(defn set-cache-name!
  "Sets the name of the cache (which ends up being part of the cache directory's
  name), and returns nil.  Default is 'urlocal'.

  Notes:
  * name must not be blank.
  * the new cache will be empty.
  * setting a new name after a previously named cache has already been populated
    will 'orphan' the prior cache. To avoid this, you should call [[reset-cache!]]
    prior to setting a new name."
  [^String name]
  (when-not (s/blank? name)
    (let [cache-dir (str cache-home name)]
      (io/make-parents (io/file (str cache-dir java.io.File/separator "dummy.txt")))  ; Make the cache dir first, so that any IO errors get thrown before we swap the atom
      (swap! cache-dir-a (constantly cache-dir))
      nil)))

(defn reset-cache!
  "Resets (i.e. deletes) the local cache, returning nil.

  Throws on IO errors."
  []
  (let [cache-dir (io/file @cache-dir-a)]
    (when (and (.exists cache-dir)
               (.isDirectory cache-dir))
      (run! #(.delete ^java.io.File %) (reverse (file-seq cache-dir))))))

(defn set-cache-check-interval-secs!
  "Sets the cache check interval, in seconds.  Default is 86,400 (24 hours)."
  [^Long cache-check-interval-secs]
  (when cache-check-interval-secs
    (swap! cache-check-interval-secs-a (constantly cache-check-interval-secs))
    nil))
