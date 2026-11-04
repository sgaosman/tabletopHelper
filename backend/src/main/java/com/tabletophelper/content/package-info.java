/**
 * Game content as data (D151, D169): loads the bundled data files into the reference tables and monsters at startup,
 * updating rows in place by natural key, and only when a loader's files or code version change. ContentLoading is
 * the public API; the loaders are internal.
 */
package com.tabletophelper.content;
