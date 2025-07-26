package cc.fastsoft.queue.utils;

public class Calculator {
    /**
     * mod by shift
     *
     */
    public static long mod(long val, int bits) {
        return val - ((val >> bits) << bits);
    }

    /**
     * multiply by shift
     *
     */
    public static long mul(long val, int bits) {
        return val << bits;
    }

    /**
     * divide by shift
     *
     */
    public static long div(long val, int bits) {
        return val >> bits;
    }
}
