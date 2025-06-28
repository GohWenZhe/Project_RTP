package project_rt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class TradingSystemGUI_EnhancedConcurrency {
    private static final Map<String, String> prices = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final List<String> tradeLogs = new CopyOnWriteArrayList<>();
    private static final ReentrantLock fileLock = new ReentrantLock();
    private static String username = "Anonymous";
    private static boolean simulateDeadlock = false;

    private static final ReentrantLock lock1 = new ReentrantLock();
    private static final ReentrantLock lock2 = new ReentrantLock();

    public static void main(String[] args) {
        JFrame frame = new JFrame("Real-Time Trading System");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 500);
        frame.setLayout(new BorderLayout());

        JPanel topPanel = new JPanel();
        String[] symbols = {"AAPL", "GOOGL", "MSFT"};
        JComboBox<String> symbolBox = new JComboBox<>(symbols);
        JTextField qtyField = new JTextField("100", 5);
        JTextField nameField = new JTextField("Enter your name", 12);
        JButton buyBtn = new JButton("Buy");
        JButton summaryBtn = new JButton("Show Summary");
        JCheckBox deadlockBox = new JCheckBox("Simulate Deadlock");

        topPanel.add(symbolBox);
        topPanel.add(new JLabel("Qty:"));
        topPanel.add(qtyField);
        topPanel.add(nameField);
        topPanel.add(buyBtn);
        topPanel.add(summaryBtn);
        topPanel.add(deadlockBox);

        JTextArea logArea = new JTextArea();
        JTextArea priceArea = new JTextArea(5, 15);
        priceArea.setEditable(false);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        JScrollPane priceScroll = new JScrollPane(priceArea);

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(logScroll, BorderLayout.CENTER);
        frame.add(priceScroll, BorderLayout.EAST);

        deadlockBox.addActionListener(e -> simulateDeadlock = deadlockBox.isSelected());

        buyBtn.addActionListener((ActionEvent e) -> {
            String symbol = (String) symbolBox.getSelectedItem();
            int qty = Integer.parseInt(qtyField.getText());
            username = nameField.getText().trim().isEmpty() ? "Anonymous" : nameField.getText().trim();

            Runnable tradeTask = () -> {
                double price = fetchPrice(symbol);
                String log = String.format("[Trade] %s bought %d of %s @ %.2f", username, qty, symbol, price);
                logArea.append(log + "\n");
                tradeLogs.add(String.join(",", username, symbol, String.valueOf(qty), String.valueOf(price)));
                saveLocal();
            };

            Thread tradeThread = new Thread(tradeTask);
            tradeThread.start();
            try { tradeThread.join(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });

        summaryBtn.addActionListener((ActionEvent e) -> {
            logArea.append("\n--- Trade Summary (using parallelStream) ---\n");
            tradeLogs.parallelStream()
                    .map(l -> l.split(","))
                    .collect(Collectors.groupingBy(arr -> arr[0] + "-" + arr[1],
                            Collectors.summingInt(arr -> Integer.parseInt(arr[2]))))
                    .forEach((k, v) -> logArea.append(k + ": " + v + " shares\n"));
        });

        executor.submit(() -> {
            Thread priceThread = Thread.currentThread();
            while (!priceThread.isInterrupted()) {
                for (String s : symbols) prices.put(s, String.format("%.2f", fetchPrice(s)));
                SwingUtilities.invokeLater(() -> {
                    priceArea.setText(prices.entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .collect(Collectors.joining("\n")));
                });
                try { Thread.sleep(5000); } catch (InterruptedException ex) {
                    priceThread.interrupt();
                }
                if (simulateDeadlock) triggerDeadlock();
            }
        });

        frame.setVisible(true);
    }

    private static double fetchPrice(String symbol) {
        try {
            URL url = new URL("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + symbol + "&apikey=demo");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                String content = reader.lines().collect(Collectors.joining());
                int idx = content.indexOf("\"05. price\": \"");
                int start = idx + 14;
                int end = content.indexOf("\"", start);
                return Double.parseDouble(content.substring(start, end));
            }
        } catch (Exception e) {
            return Math.random() * 100 + 50;
        }
    }

    private static void saveLocal() {
        fileLock.lock();
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get("trades_local.csv"))) {
            for (String line : tradeLogs) writer.write(line + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            fileLock.unlock();
        }
    }

    private static void triggerDeadlock() {
        Thread t1 = new Thread(() -> {
            lock1.lock();
            try { Thread.sleep(100); lock2.lock(); lock2.unlock(); } catch (InterruptedException e) {} finally { lock1.unlock(); }
        });
        Thread t2 = new Thread(() -> {
            lock2.lock();
            try { Thread.sleep(100); lock1.lock(); lock1.unlock(); } catch (InterruptedException e) {} finally { lock2.unlock(); }
        });
        t1.start();
        t2.start();
    }
}
