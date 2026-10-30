package com.apkguard.app.desktop;

import com.apkguard.app.Scanner;
import com.apkguard.app.Scanner.Finding;
import com.apkguard.app.Scanner.Result;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.concurrent.ExecutionException;

/** Windows desktop front-end for the shared APK scanning engine. */
public final class Main {
    private final JFrame frame = new JFrame("APK Guard — проверка APK");
    private final JLabel fileLabel = new JLabel("Файл не выбран");
    private final JLabel statusLabel = new JLabel("Выберите APK-файл для проверки.");
    private final JTextArea report = new JTextArea();
    private final JButton scanButton = new JButton("Проверить APK");
    private File selected;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(720, 560));
        frame.setSize(820, 650);
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(new Color(245, 247, 250));

        JLabel title = new JLabel("APK Guard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        JLabel subtitle = new JLabel("Локальная проверка APK-файлов на подозрительные признаки");
        subtitle.setForeground(new Color(85, 95, 110));
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 4));
        heading.setOpaque(false);
        heading.add(title);
        heading.add(subtitle);

        JButton chooseButton = new JButton("Выбрать APK…");
        chooseButton.addActionListener(e -> chooseFile());
        scanButton.setEnabled(false);
        scanButton.addActionListener(e -> scan());
        JPanel filePanel = new JPanel(new BorderLayout(10, 8));
        filePanel.setOpaque(false);
        filePanel.add(fileLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(chooseButton);
        actions.add(scanButton);
        filePanel.add(actions, BorderLayout.SOUTH);

        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setText("Здесь появится отчёт после проверки.\n\nВажно: результат является эвристической оценкой, а не гарантией безопасности. APK Guard не заменяет антивирус и не отправляет файл в интернет.");
        JScrollPane scroll = new JScrollPane(report);
        scroll.setBorder(BorderFactory.createTitledBorder("Отчёт проверки"));

        statusLabel.setBorder(new EmptyBorder(4, 2, 0, 2));
        statusLabel.setForeground(new Color(70, 80, 95));
        JPanel top = new JPanel(new BorderLayout(0, 14));
        top.setOpaque(false);
        top.add(heading, BorderLayout.NORTH);
        top.add(filePanel, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setVisible(true);
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите APK-файл");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Android APK (*.apk)", "apk"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.getSelectedFile();
            fileLabel.setText(selected.getAbsolutePath());
            scanButton.setEnabled(true);
            report.setText("Файл выбран. Нажмите «Проверить APK».\n\nПроверка выполняется локально на этом компьютере.");
            statusLabel.setText("Готов к проверке.");
        }
    }

    private void scan() {
        if (selected == null || !selected.isFile()) return;
        final File target = selected;
        scanButton.setEnabled(false);
        statusLabel.setText("Идёт проверка…");
        report.setText("Анализ APK, пожалуйста, подождите…");

        new SwingWorker<Result, Void>() {
            @Override protected Result doInBackground() {
                return Scanner.scan(target, null, null);
            }
            @Override protected void done() {
                scanButton.setEnabled(true);
                try {
                    showResult(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Проверка прервана.");
                } catch (ExecutionException ex) {
                    report.setText("Не удалось выполнить проверку: " + ex.getCause());
                    statusLabel.setText("Ошибка проверки.");
                }
            }
        }.execute();
    }

    private void showResult(Result r) {
        StringBuilder out = new StringBuilder();
        out.append("Файл: ").append(r.name).append('\n');
        out.append("SHA-256: ").append(r.sha256 == null ? "не вычислен" : r.sha256).append("\n\n");
        out.append("ОЦЕНКА РИСКА: ").append(r.score).append(" / 100\n");
        out.append("ВЕРДИКТ: ").append(r.verdict).append("\n\n");
        out.append("НАЙДЕННЫЕ ПРИЗНАКИ\n");
        out.append("----------------------------------------\n");
        if (r.findings.isEmpty()) {
            out.append("Подозрительных признаков по используемым правилам не обнаружено.\n");
        } else {
            for (Finding f : r.findings) {
                out.append("[Уровень ").append(f.layer).append("] ")
                   .append(f.msg).append(" (+").append(f.points).append(")\n");
            }
        }
        out.append("\nРЕКОМЕНДАЦИЯ\n").append(Scanner.advice(r.level));
        out.append("\n\nОГРАНИЧЕНИЯ\nЭто эвристическая проверка структуры, разрешений и строк в DEX-коде. Она может пропустить новые угрозы или отметить безопасное приложение как подозрительное. Отсутствие находок не гарантирует безопасность.");
        report.setText(out.toString());
        report.setCaretPosition(0);
        statusLabel.setText("Проверка завершена.");
    }
}
