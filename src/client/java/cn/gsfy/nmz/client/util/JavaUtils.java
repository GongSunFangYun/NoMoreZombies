package cn.gsfy.nmz.client.util;

/**
 * 通用算法工具——把数组下标校验、二分查找这类无状态小逻辑收在一起，
 * 各处直接静态调用，不持有任何数据。
 *
 * <p>这里的方法都是纯函数：入参出参不含副作用，可放心在 tick / 渲染 / 后台线程
 * 任意调用；下标校验特意区分「数组为 null」与「下标越界」，让上层少写判空。
 */
public final class JavaUtils {

    /** 调用方取数前校验一维数组下标：非空数组且 0 ≤ index < 长度才算合法，先判 null 防空指针。 */
    public static boolean isValidIndex(int[] array, int index) {
        return array != null && index >= 0 && index < array.length;
    }

    /** 校验二维数组 (row, col)：外层越界或内层为 null 都算非法，允许参差不齐的数组。 */
    public static boolean isValidIndex(int[][] array, int row, int col) {
        if (array == null || row < 0 || row >= array.length) {
            return false;
        }
        int[] inner = array[row];
        return inner != null && col >= 0 && col < inner.length;
    }

    /**
     * 在升序数组里二分找 target 的「插入位置」：返回第一个大于等于 target 的下标，
     * 全小于 target 则返回数组长度——波次时间表按 tick 定位时用，二分保证多次
     * 调用都稳定在 O(log n)，不会拖慢高频路径。
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

    private JavaUtils() {
    }
}
