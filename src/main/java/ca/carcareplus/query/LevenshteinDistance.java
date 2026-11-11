package ca.carcareplus.query;

/**
 * Edit distance: the smallest number of single-character inserts, deletes and
 * substitutions that turn one word into another. "kitten" -> "sitting" = 3.
 *
 * Dynamic programming: dp[i][j] = distance between the first i characters of a
 * and the first j characters of b.
 * <pre>
 *   dp[i][0] = i            (delete all i characters)
 *   dp[0][j] = j            (insert all j characters)
 *   dp[i][j] = dp[i-1][j-1]                              if a[i] == b[j]
 *            = 1 + min(dp[i-1][j]    delete a[i],
 *                      dp[i][j-1]    insert b[j],
 *                      dp[i-1][j-1]) substitute          otherwise
 * </pre>
 * Note: swapping two letters ("battrey" -> "battery") costs 2 here (two
 * substitutions). The Damerau variant would count it as 1.
 */
public final class LevenshteinDistance {

    private LevenshteinDistance() {
        // static helper methods only
    }

    /**
     * Full matrix, O(m * n) time and space. Kept so the demo can print the table.
     * null is treated as "".
     */
    public static int[][] matrix(String a, String b) {
        String s = a == null ? "" : a;
        String t = b == null ? "" : b;
        int[][] dp = new int[s.length() + 1][t.length() + 1];
        for (int i = 0; i <= s.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= t.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= s.length(); i++) {
            for (int j = 1; j <= t.length(); j++) {
                if (s.charAt(i - 1) == t.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp;
    }

    /** Edit distance between two words (null is treated as ""). */
    public static int distance(String a, String b) {
        int[][] dp = matrix(a, b);
        return dp[dp.length - 1][dp[0].length - 1];
    }

    /**
     * Edit distance, but gives up as soon as it is sure the answer is above max.
     * Used by the spell checker, which compares one word with thousands.
     * <p>
     * Two speed-ups:
     * 1. If the lengths differ by more than max, at least that many inserts or
     *    deletes are needed, so we stop at once: O(1).
     * 2. Only two rows of the matrix are kept (O(n) space). Every value in a row
     *    is at least the smallest value of the row before, so once a whole row is
     *    above max, the final answer will be too and we stop early.
     *
     * @return the distance if it is at most max, otherwise max + 1
     */
    public static int distanceWithin(String a, String b, int max) {
        String s = a == null ? "" : a;
        String t = b == null ? "" : b;
        if (max < 0) {
            return max + 1;
        }
        if (Math.abs(s.length() - t.length()) > max) {
            return max + 1;
        }
        int[] previous = new int[t.length() + 1];
        int[] current = new int[t.length() + 1];
        for (int j = 0; j <= t.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= s.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= t.length(); j++) {
                if (s.charAt(i - 1) == t.charAt(j - 1)) {
                    current[j] = previous[j - 1];
                } else {
                    current[j] = 1 + Math.min(previous[j - 1], Math.min(previous[j], current[j - 1]));
                }
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] swap = previous; // the current row becomes the previous one
            previous = current;
            current = swap;
        }
        return Math.min(previous[t.length()], max + 1);
    }
}
