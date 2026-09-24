package dev.mars.qraft.controller.http;

/** Auth-replaceable source of the catalog identity attached to an HTTP request. */
interface RequestContext {
    String tenantId();

    String namespace();

    String nodeId();
}
