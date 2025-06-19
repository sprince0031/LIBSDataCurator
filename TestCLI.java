import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;

public class TestCLI {
    public static void main(String[] args) {
        CommonUtils commonUtils = new CommonUtils();
        String[][] testCases = {
            {"-c", "some_guid"}, // should pass
            {"-s"}, // should pass
            {"-s", "some_series_key"}, // should pass
            {}, // should fail, neither -c nor -s
            {"-c", "some_guid", "-s", "some_series_key"}, // should fail, both -c and -s
            {"-c", "some_guid", "-s"} // should fail, both -c and -s
        };

        String[] descriptions = {
            "Test Case 1: -c some_guid (should pass)",
            "Test Case 2: -s (should pass)",
            "Test Case 3: -s some_series_key (should pass)",
            "Test Case 4: (empty args) (should fail)",
            "Test Case 5: -c some_guid -s some_series_key (should fail)",
            "Test Case 6: -c some_guid -s (should fail)"
        };

        boolean[] expectedResults = {
            true, // pass
            true, // pass
            true, // pass
            false, // fail
            false, // fail
            false  // fail
        };

        System.out.println("Starting CLI argument handler tests...");

        for (int i = 0; i < testCases.length; i++) {
            System.out.println("\n" + descriptions[i]);
            String[] currentArgs = testCases[i];
            CommandLine cmd = commonUtils.getTerminalArgHandler(currentArgs);
            boolean result = (cmd != null);

            if (result == expectedResults[i]) {
                System.out.println("Result: PASS");
            } else {
                System.out.println("Result: FAIL");
                System.out.println("  Expected: " + (expectedResults[i] ? "CommandLine object" : "null"));
                System.out.println("  Actual:   " + (result ? "CommandLine object" : "null"));
            }
        }
        System.out.println("\nCLI argument handler tests finished.");
    }
}
