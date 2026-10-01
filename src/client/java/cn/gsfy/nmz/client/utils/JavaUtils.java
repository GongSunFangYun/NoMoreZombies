package cn.gsfy.nmz.client.utils;

/**
 * Common algorithm helpers—small stateless bits like array index validation
 * and binary search gathered in one place; callers call them statically and
 * no data is held.
 *
 * <p>Every method here is a pure function: no side effects in parameters or
 * returns, safe to call from a tick / render / background thread. The index
 * validators deliberately distinguish "array is null" from "index out of
 * range", letting upper layers skip a null check.
 */
public final class JavaUtils {

    /**
     * Validates a 1-D array index before the caller reads—legal only when
     * the array is non-null and {@code 0<=index<length}.
     *
     * @param array the array to check; may be {@code null}
     * @param index the index to check
     * @return true when the array is non-null and the index is in range
     */
    public static boolean isValidIndex(int[] array, int index) {
        return array != null && index >= 0 && index < array.length;
    }

    /**
     * Validates a ragged 2-D array's {@code (row, col)}—an outer row out of
     * range or the matched inner array being {@code null} is illegal; the
     * rest is decided by the inner array's real length.
     *
     * <p>It is <b>not</b> a trivial reuse of
     * {@link #isValidIndex(int[], int)}: inner rows are ragged, so the inner
     * array must be taken out first, then measured. The two overloads'
     * criteria (null / negative / out-of-range) are deliberately written in
     * the same order, so reading one tells you the other.
     *
     * @param array the 2-D array to check; may be {@code null}
     * @param row the outer index
     * @param col the inner index
     * @return true when both array layers exist and both indexes are in range
     */
    public static boolean isValidIndex(int[][] array, int row, int col) {
        if (array == null || row < 0 || row >= array.length) {
            return false;
        }
        // The inner uses the 1-D overload (inner rows are ragged, so the row
        // is fetched first); the shape matches the one above: null, then
        // negative, then out-of-range
        return isValidIndex(array[row], col);
    }

    /**
     * Binary search for {@code target} in an ascending array: on a hit
     * returns the index of some equal element; on a miss returns the
     * insertion position that would keep the array ascending; with duplicate
     * values it does not guarantee returning the first equal element.
     * The wave time table's tick lookup goes through this O(log n) path. The
     * midpoint uses the overflow-safe {@code left+(right-left)/2}—the
     * direct add-then-divide overflows when both ends are large.
     *
     * @param array a non-{@code null} array in ascending order
     * @param target the value to find or insert
     * @return a hit index, or an insertion position in {@code 0..array.length}
     *  on a miss
     * @throws NullPointerException when the array is {@code null}
     */
    public static int findInsertPosition(int[] array, int target) {
        int left = 0;
        int right = array.length - 1;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            if (array[mid] == target) {
                return mid;
            } else if (array[mid] < target) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return left;
    }

    /**
     * Formats a health value: rounds to {@code decimals} places, dropping
     * the fraction when the result is an integer.
     *
     * <p>Health jitters on the decimal places often; printing the float
     * directly yields a meaningless long tail, so the whole mod shares this
     * one accounting—health bars and damage numbers both read from here, and
     * "the same health reading becomes the same string" is the premise for
     * the two to confirm each other.
     *
     * @param value the health value or delta; the absolute value is used
     * @param decimals the number of decimal places to keep
     * @return a digits-only string (an integer has no decimal point)
     */
    public static String roundToDecimal(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        double rounded = Math.round(Math.abs(value) * scale) / scale;

        if (rounded == Math.floor(rounded) && !Double.isInfinite(rounded)) {
            return String.valueOf((long) rounded);
        }

        return String.valueOf(rounded);
    }

    private JavaUtils() {
    }
}