module consulo.logging.api {
  requires consulo.util.nodep;

  exports consulo.logging;
  exports consulo.logging.attachment;

  // TODO [VISTALL] this package must be exported only to impl module!
  exports consulo.logging.internal;
}