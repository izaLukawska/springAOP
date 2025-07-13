package org.lukawska.springaop.locking;

public enum LockStrategy {
    FAIL_FAST,
    WAIT_AND_RETRY,
    SKIP_EXECUTION

}
