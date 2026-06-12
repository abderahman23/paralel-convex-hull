import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

class Point {
    double x, y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return String.format("(%.4f, %.4f)", x, y);
    }
}

public class ParallelConvexHull {

    private static double crossProduct(Point p1, Point p2, Point p3) {
        return (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x);
    }

    public static boolean isConvexSerial(List<Point> points) {
        int n = points.size();
        if (n < 3) return false;

        boolean hasPositive = false;
        boolean hasNegative = false;

        for (int i = 0; i < n; i++) {
            Point p1 = points.get(i);
            Point p2 = points.get((i + 1) % n);
            Point p3 = points.get((i + 2) % n);

            double cross = crossProduct(p1, p2, p3);

            if (cross > 1e-10) hasPositive = true;
            else if (cross < -1e-10) hasNegative = true;

            if (hasPositive && hasNegative) return false;
        }

        return true;
    }

    public static boolean isConvexParallel(List<Point> points, int numThreads) 
            throws InterruptedException, ExecutionException {

        int n = points.size();
        if (n < 3) return false;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<CrossResult>> futures = new ArrayList<>();

        int chunkSize = (n + numThreads - 1) / numThreads;

        for (int t = 0; t < numThreads; t++) {
            final int start = t * chunkSize;
            final int end = Math.min(start + chunkSize, n);

            if (start >= n) break;

            Callable<CrossResult> task = () -> {
                boolean localPositive = false;
                boolean localNegative = false;

                for (int i = start; i < end; i++) {
                    Point p1 = points.get(i);
                    Point p2 = points.get((i + 1) % n);
                    Point p3 = points.get((i + 2) % n);

                    double cross = crossProduct(p1, p2, p3);

                    if (cross > 1e-10) localPositive = true;
                    else if (cross < -1e-10) localNegative = true;

                    if (localPositive && localNegative) {
                        return new CrossResult(true, true);
                    }
                }
                return new CrossResult(localPositive, localNegative);
            };

            futures.add(executor.submit(task));
        }

        boolean globalPositive = false;
        boolean globalNegative = false;

        for (Future<CrossResult> future : futures) {
            CrossResult result = future.get();
            if (result.hasPositive) globalPositive = true;
            if (result.hasNegative) globalNegative = true;

            if (globalPositive && globalNegative) {
                executor.shutdownNow();
                return false;
            }
        }

        executor.shutdown();
        return true;
    }

    static class CrossResult {
        boolean hasPositive;
        boolean hasNegative;

        CrossResult(boolean pos, boolean neg) {
            this.hasPositive = pos;
            this.hasNegative = neg;
        }
    }

    public static boolean isConvexParallelLatch(List<Point> points, int numThreads) 
            throws InterruptedException {

        int n = points.size();
        if (n < 3) return false;

        final boolean[] results = new boolean[numThreads * 2];
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        int chunkSize = (n + numThreads - 1) / numThreads;

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            final int start = t * chunkSize;
            final int end = Math.min(start + chunkSize, n);

            if (start >= n) {
                latch.countDown();
                continue;
            }

            executor.submit(() -> {
                try {
                    boolean localPos = false;
                    boolean localNeg = false;

                    for (int i = start; i < end; i++) {
                        Point p1 = points.get(i);
                        Point p2 = points.get((i + 1) % n);
                        Point p3 = points.get((i + 2) % n);

                        double cross = crossProduct(p1, p2, p3);
                        if (cross > 1e-10) localPos = true;
                        else if (cross < -1e-10) localNeg = true;

                        if (localPos && localNeg) break;
                    }

                    results[threadId * 2] = localPos;
                    results[threadId * 2 + 1] = localNeg;
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        boolean globalPos = false;
        boolean globalNeg = false;
        for (int i = 0; i < numThreads; i++) {
            if (results[i * 2]) globalPos = true;
            if (results[i * 2 + 1]) globalNeg = true;
            if (globalPos && globalNeg) return false;
        }

        return true;
    }

    public static boolean isConvexForkJoin(List<Point> points) {
        ForkJoinPool pool = new ForkJoinPool();
        try {
            CrossResult result = pool.invoke(new ConvexTask(points, 0, points.size()));
            return !(result.hasPositive && result.hasNegative);
        } finally {
            pool.shutdown();
        }
    }

    static class ConvexTask extends RecursiveTask<CrossResult> {
        private static final int THRESHOLD = 5000;
        private List<Point> points;
        private int start, end;

        ConvexTask(List<Point> points, int start, int end) {
            this.points = points;
            this.start = start;
            this.end = end;
        }

        @Override
        protected CrossResult compute() {
            int n = points.size();

            if (end - start <= THRESHOLD) {
                boolean pos = false, neg = false;
                for (int i = start; i < end; i++) {
                    Point p1 = points.get(i);
                    Point p2 = points.get((i + 1) % n);
                    Point p3 = points.get((i + 2) % n);

                    double cross = crossProduct(p1, p2, p3);
                    if (cross > 1e-10) pos = true;
                    else if (cross < -1e-10) neg = true;

                    if (pos && neg) return new CrossResult(true, true);
                }
                return new CrossResult(pos, neg);
            }

            int mid = (start + end) / 2;
            ConvexTask left = new ConvexTask(points, start, mid);
            ConvexTask right = new ConvexTask(points, mid, end);

            left.fork();
            CrossResult rightResult = right.compute();
            CrossResult leftResult = left.join();

            return new CrossResult(
                leftResult.hasPositive || rightResult.hasPositive,
                leftResult.hasNegative || rightResult.hasNegative
            );
        }
    }

    public static void runPerformanceTests() throws Exception {
        System.out.println("============================================================");
        System.out.println("    PARALEL CONVEX HULL KONTROLU - PERFORMANS TESTLERI");
        System.out.println("============================================================\n");

        System.out.println("--- TEST 1: Basit Convex Kare (4 nokta) ---");
        List<Point> square = createSquare();
        runTest(square, "Kare", new int[]{1, 2, 4});

        System.out.println("\n--- TEST 2: Concave Poligon (5 nokta) ---");
        List<Point> concave = createConcave();
        runTest(concave, "Concave", new int[]{1, 2, 4});

        System.out.println("\n--- TEST 3: Buyuk Convex Poligon (100,000 nokta) ---");
        List<Point> largeConvex = createLargeConvex(100000);
        runTest(largeConvex, "Buyuk Convex", new int[]{1, 2, 4, 8, 16});

        System.out.println("\n--- TEST 4: Buyuk Concave Poligon (100,000 nokta) ---");
        List<Point> largeConcave = createLargeConcave(100000);
        runTest(largeConcave, "Buyuk Concave", new int[]{1, 2, 4, 8, 16});

        System.out.println("\n--- TEST 5: Cok Buyuk Veri Seti (1,000,000 nokta) ---");
        List<Point> hugeConvex = createLargeConvex(1000000);
        runTest(hugeConvex, "Cok Buyuk Convex", new int[]{1, 2, 4, 8, 16, 32});

        System.out.println("\n--- TEST 6: Fork/Join Framework Karsilastirmasi ---");
        runForkJoinTest(createLargeConvex(500000));
    }

    private static void runTest(List<Point> points, String name, int[] threadCounts) 
            throws Exception {
        System.out.printf("Poligon: %s | Nokta sayisi: %d\n", name, points.size());
        System.out.println("Thread\tSeri(ms)\tParalel(ms)\tHizlanma\tSonuc");
        System.out.println("-----------------------------------------------------------");

        long startSerial = System.nanoTime();
        boolean serialResult = isConvexSerial(points);
        long serialTime = System.nanoTime() - startSerial;
        double serialMs = serialTime / 1_000_000.0;

        for (int threads : threadCounts) {
            isConvexParallel(points, threads);

            long start = System.nanoTime();
            boolean parallelResult = isConvexParallel(points, threads);
            long parallelTime = System.nanoTime() - start;
            double parallelMs = parallelTime / 1_000_000.0;

            double speedup = serialMs / parallelMs;

            System.out.printf("%d\t%.3f\t\t%.3f\t\t%.2fx\t\t%s\n",
                threads, serialMs, parallelMs, speedup,
                serialResult == parallelResult ? "ESLESIYOR" : "HATA!");
        }
    }

    private static void runForkJoinTest(List<Point> points) {
        System.out.println("Nokta sayisi: " + points.size());

        long t1 = System.nanoTime();
        boolean r1 = isConvexSerial(points);
        double ms1 = (System.nanoTime() - t1) / 1_000_000.0;

        long t2 = System.nanoTime();
        boolean r2 = false;
        try { r2 = isConvexParallel(points, 8); } catch (Exception e) { e.printStackTrace(); }
        double ms2 = (System.nanoTime() - t2) / 1_000_000.0;

        long t3 = System.nanoTime();
        boolean r3 = isConvexForkJoin(points);
        double ms3 = (System.nanoTime() - t3) / 1_000_000.0;

        System.out.println("Yontem\t\t\tZaman(ms)\tHizlanma\tSonuc");
        System.out.println("-----------------------------------------------------------");
        System.out.printf("Seri\t\t\t%.3f\t\t1.00x\t\t%b\n", ms1, r1);
        System.out.printf("Thread Pool (8)\t\t%.3f\t\t%.2fx\t\t%b\n", ms2, ms1/ms2, r2);
        System.out.printf("Fork/Join\t\t%.3f\t\t%.2fx\t\t%b\n", ms3, ms1/ms3, r3);
    }

    static List<Point> createSquare() {
        List<Point> pts = new ArrayList<>();
        pts.add(new Point(0, 0));
        pts.add(new Point(2, 0));
        pts.add(new Point(2, 2));
        pts.add(new Point(0, 2));
        return pts;
    }

    static List<Point> createConcave() {
        List<Point> pts = new ArrayList<>();
        pts.add(new Point(0, 0));
        pts.add(new Point(4, 1));
        pts.add(new Point(2, 1));
        pts.add(new Point(4, 3));
        pts.add(new Point(0, 2));
        return pts;
    }

    static List<Point> createLargeConvex(int n) {
        List<Point> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            pts.add(new Point(Math.cos(angle), Math.sin(angle)));
        }
        return pts;
    }

    static List<Point> createLargeConcave(int n) {
        List<Point> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double radius = (i % 2 == 0) ? 1.0 : 0.6;
            pts.add(new Point(radius * Math.cos(angle), radius * Math.sin(angle)));
        }
        return pts;
    }

    public static void main(String[] args) throws Exception {
        runPerformanceTests();
    }
}
