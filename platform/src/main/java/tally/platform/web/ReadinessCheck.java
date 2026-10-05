package tally.platform.web;

/** One dependency that must be reachable before the service reports ready. */
public interface ReadinessCheck {

  String name();

  /** Returns within 1000 ms and never throws. */
  boolean up();
}
