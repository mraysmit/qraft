package dev.mars.qraft.controller.runtime;

public interface AsyncResult<T> {
    boolean succeeded();
    boolean failed();
    T result();
    Throwable cause();
}
