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
  "Returns an InputStream for the content retrieved from url (a String,
  java.net.URL or java.net.URI) and caching it, or nil if url is nil or
  unsupported (i.e. is not an http(s) URL).

  The options map provides these tunables, all of them optional:
  * `:connect-timeout` (int, default=1000): the number of milliseconds to wait when
    establishing the socket connection before timing out
  * `:read-timeout` (int, default=1000): the number of milliseconds to wait when
    reading content over the socket before timing out
  * `:follows-redirects?` (boolean, default=false): whether to follow redirects
    (HTTP status codes 301, 302) if the server issues one
  * `:request-headers` (map with string keys and values): a map of request
    headers to send along with the request

  Throws on IO errors."
  ([url] (input-stream url nil))
  ([url {:keys [connect-timeout read-timeout follow-redirects? request-headers]
         :or   {connect-timeout   1000
                read-timeout      1000
                follow-redirects? false
                request-headers   {"User-Agent" "com.github.pmonks/urlocal"}}
         :as   opts}]
   (when-let [u (io/as-url url)]
     (when (s/starts-with? (s/lower-case (.getProtocol u)) "http")
       (uic/prep-cache! u opts)
       (io/input-stream (uic/url->content-file u))))))

(defn set-cache-name!
  "Sets the name of the cache (which ends up being part of the cache directory's
  name), and returns nil.  Default is 'urlocal'.

  Notes:
  * name must not be blank.
  * the new cache may not be empty if it was previously populated.
  * setting a new name after a previously named cache has already been populated
    will 'orphan' the prior cache. To avoid this, you should call `reset-cache!`
    prior to setting a new name.

  Throws on IO errors."
  [^String name]
  (when-not (s/blank? name)
    (let [cache-dir (str xdg/cache-home name)]
      (io/make-parents (io/file (str cache-dir java.io.File/separator "dummy.txt")))  ; Make the cache dir first, so that any IO errors get thrown before we swap the atom
      (swap! uic/cache-dir-a (constantly cache-dir))
      nil)))

(defn reset-cache!
  "Resets (i.e. deletes) the local cache, returning nil.

  Throws on IO errors."
  []
  (let [cache-dir (io/file @uic/cache-dir-a)]
    (when (and (.exists cache-dir)
               (.isDirectory cache-dir))
      (run! #(.delete ^java.io.File %) (reverse (file-seq cache-dir))))))

(defn remove-cache-entry!
  "Removes the cache entry for a single URL, if it exists. Returns nil.

  Throws on IO errors."
  [url]
  (uic/remove-cache-entry! url))

(defn set-cache-check-interval-secs!
  "Sets the cache check interval, in seconds.  Default is 86,400 (24 hours)."
  [^Long cache-check-interval-secs]
  (when cache-check-interval-secs
    (swap! uic/cache-check-interval-secs-a (constantly cache-check-interval-secs))
    nil))
