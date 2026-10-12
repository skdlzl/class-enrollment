/** Resolve the real Java executable behind Windows Oracle javapath launchers. */
class JavaRuntimeHome {
    public static void main(String[] args) {
        System.out.println(System.getProperty("java.home"));
    }
}
