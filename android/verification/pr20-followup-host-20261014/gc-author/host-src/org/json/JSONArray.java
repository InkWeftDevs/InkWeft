package org.json;

/** Captures the actual compiled Boundary.json() constructor argument.
 * This is deliberately NOT an Android JSON implementation or serialization test. */
public final class JSONArray {
    public final java.util.List<?> values;
    public JSONArray(java.util.Collection<?> input) {
        values = new java.util.ArrayList<>(input);
    }
}
