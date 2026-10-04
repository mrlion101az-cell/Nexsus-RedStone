package com.nexuscraft.nexusredstone;

/** The signal strengths a component sees this step, relative to its facing: the two sides, the
 *  back, and one "external" channel that wireless receivers and sensors feed in. */
record Inputs(int left, int right, int back, int external) {

    static final Inputs NONE = new Inputs(0, 0, 0, 0);

    int strongest() {
        return Math.max(Math.max(left, right), back);
    }
}
