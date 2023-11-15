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

(ns urlocal.impl.xdg
  "Vars related to the XDG Base Directory Specification: https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
  This namespace is not part of the public API of urlocal and may change without
  notice."
  (:require [clojure.string :as s]))

(def cache-home
  "The base location of the XDG 'cache home' directory, as per the specification.
  Does not include include any application-specific sub-directories, and always
  ends with a path separator character."
  (let [xdg-cache-home (System/getenv "XDG_CACHE_HOME")]
    (if (s/blank? xdg-cache-home)
      (str (System/getProperty "user.home") java.io.File/separator ".cache" java.io.File/separator)
      (if (s/ends-with? xdg-cache-home java.io.File/separator)
        xdg-cache-home
        (str xdg-cache-home java.io.File/separator)))))
