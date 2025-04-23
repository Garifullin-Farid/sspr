package com.garifullin;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskSplitAdapter;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setClientMode(true);
        cfg.setPeerClassLoadingEnabled(true);

        TcpDiscoverySpi discoverySpi = new TcpDiscoverySpi();
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();

        List<String> addresses = new ArrayList<>();
        addresses.add("192.168.1.202:47500..47509"); // Замените на IP вашего сервера Ignite
        ipFinder.setAddresses(addresses);
        discoverySpi.setIpFinder(ipFinder);
        discoverySpi.setSocketTimeout(5000);
        discoverySpi.setAckTimeout(5000);
        cfg.setDiscoverySpi(discoverySpi);

        try (Ignite ignite = Ignition.start(cfg)) {
            System.out.println("Connected to cluster. Number of nodes: " + ignite.cluster().nodes().size());

            int[][] matrix = generateMatrix(500, 500);

            long startTime = System.nanoTime();
            ClusterGroup computeGroup = ignite.cluster().forServers();
            Double average = ignite.compute(computeGroup).execute(new MatrixAverageTask(matrix), null);
            long endTime = System.nanoTime();

            System.out.println("Average value of matrix elements: " + average);
            System.out.println("Execution time: " + (endTime - startTime) / 1_000_000 + " ms");
        } catch (Exception e) {
            System.err.println("Failed to start Ignite or execute computation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int[][] generateMatrix(int rows, int cols) {
        int[][] matrix = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = (int) (Math.random() * 100);
            }
        }
        return matrix;
    }

    private static class MatrixAverageTask extends ComputeTaskSplitAdapter<Void, Double> implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int[][] matrix;

        public MatrixAverageTask(int[][] matrix) {
            this.matrix = matrix;
        }

        @Override
        protected List<ComputeJob> split(int gridSize, Void arg) {
            List<ComputeJob> jobs = new ArrayList<>();
            int n = matrix.length;
            int chunkSize = (n + gridSize - 1) / gridSize; // Разделяем матрицу на части

            for (int i = 0; i < n; i += chunkSize) {
                int end = Math.min(i + chunkSize, n);
                jobs.add(new MatrixAverageJob(matrix, i, end));
            }

            return jobs;
        }

        @Override
        public Double reduce(List<ComputeJobResult> results) {
            double totalSum = 0;
            long totalCount = 0;

            for (ComputeJobResult res : results) {
                SumCount result = res.getData();
                totalSum += result.sum;
                totalCount += result.count;
            }

            return totalCount == 0 ? 0 : totalSum / totalCount;
        }
    }

    private static class MatrixAverageJob extends ComputeJobAdapter implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int[][] matrix;
        private final int startRow;
        private final int endRow;

        public MatrixAverageJob(int[][] matrix, int startRow, int endRow) {
            this.matrix = matrix;
            this.startRow = startRow;
            this.endRow = endRow;
        }

        @Override
        public SumCount execute() {
            double sum = 0;
            long count = 0;

            for (int i = startRow; i < endRow; i++) {
                for (int j = 0; j < matrix[i].length; j++) {
                    sum += matrix[i][j];
                    count++;
                }
            }

            return new SumCount(sum, count);
        }
    }

    private static class SumCount implements Serializable {
        private static final long serialVersionUID = 1L;
        final double sum;
        final long count;

        public SumCount(double sum, long count) {
            this.sum = sum;
            this.count = count;
        }
    }
}