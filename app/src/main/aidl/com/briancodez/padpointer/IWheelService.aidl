package com.briancodez.padpointer;

// Deliberately exposes only scrolling, not arbitrary input injection or shell commands.
interface IWheelService {
    void destroy() = 16777114;
    String initialize() = 1;
    boolean scroll(float x, float y, float horizontal, float vertical, long eventTime) = 2;
}
