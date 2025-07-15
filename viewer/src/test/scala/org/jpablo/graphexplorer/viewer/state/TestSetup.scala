package org.jpablo.graphexplorer.viewer.state

import scala.scalajs.js

object TestSetup {
  def setupMockStorage(): Unit =
    js.eval(
      """
        if (typeof window === 'undefined') {
          global.window = {};
        }
        
        if (typeof window.localStorage === 'undefined') {
          var storage = {};
          
          window.localStorage = {
            getItem: function(key) {
              return storage[key] || null;
            },
            setItem: function(key, value) {
              storage[key] = value.toString();
            },
            removeItem: function(key) {
              delete storage[key];
            },
            clear: function() {
              storage = {};
            },
            get length() {
              return Object.keys(storage).length;
            },
            key: function(index) {
              var keys = Object.keys(storage);
              return keys[index] || null;
            }
          };
        } else {
          window.localStorage.clear();
        }
      """
    )

  def cleanupMockStorage(): Unit =
    js.eval(
      """
        if (typeof window !== 'undefined' && window.localStorage && window.localStorage.clear) {
          window.localStorage.clear();
        }
      """
    )
}
