package com.example.myapplication.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NeuralNetworkBackgroundView extends View {

    private static class Node {
        float x, y, vx, vy, radius;
        Node(float x, float y, float vx, float vy, float radius) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
        }
    }

    private static class Connection {
        int from, to;
        float opacity;
        Connection(int from, int to, float opacity) {
            this.from = from;
            this.to = to;
            this.opacity = opacity;
        }
    }

    private List<Node> nodes = new ArrayList<>();
    private List<Connection> connections = new ArrayList<>();
    private float mouseX = -1000f;
    private float mouseY = -1000f;

    private Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Random random = new Random();
    private int nodeCount = 50;

    public NeuralNetworkBackgroundView(Context context) {
        super(context);
        init();
    }

    public NeuralNetworkBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NeuralNetworkBackgroundView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setColor(Color.argb(255, 255, 218, 3));
        linePaint.setStrokeWidth(3f);

        nodePaint.setColor(Color.argb(255, 255, 218, 3));
        nodePaint.setStyle(Paint.Style.FILL);

        glowPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        nodes.clear();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new Node(
                    random.nextFloat() * w,
                    random.nextFloat() * h,
                    (random.nextFloat() - 0.5f) * 1.5f,
                    (random.nextFloat() - 0.5f) * 1.5f,
                    random.nextFloat() * 3 + 3
            ));
        }
    }

    private void updateConnections(float densityThreshold) {
        connections.clear();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node n1 = nodes.get(i);
                Node n2 = nodes.get(j);
                float dx = n1.x - n2.x;
                float dy = n1.y - n2.y;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance < densityThreshold) {
                    connections.add(new Connection(i, j, 1f - distance / densityThreshold));
                }
            }
        }
    }

    public void updateTouch(float x, float y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        // Base connection threshold
        float densityThreshold = 250f;
        float interactionRadius = 400f;

        updateConnections(densityThreshold);

        // draw connections
        for (Connection conn : connections) {
            Node fromNode = nodes.get(conn.from);
            Node toNode = nodes.get(conn.to);

            float dx = fromNode.x - toNode.x;
            float dy = fromNode.y - toNode.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);

            // Mouse influence on connections
            float mouseDx1 = fromNode.x - mouseX;
            float mouseDy1 = fromNode.y - mouseY;
            float mouseDx2 = toNode.x - mouseX;
            float mouseDy2 = toNode.y - mouseY;
            float mouseDist1 = (float) Math.sqrt(mouseDx1 * mouseDx1 + mouseDy1 * mouseDy1);
            float mouseDist2 = (float) Math.sqrt(mouseDx2 * mouseDx2 + mouseDy2 * mouseDy2);
            float mouseInfluence = Math.max(0, 1f - Math.min(mouseDist1, mouseDist2) / interactionRadius);

            if (distance < densityThreshold) {
                float opacity = (1f - distance / densityThreshold) * (0.3f + mouseInfluence * 0.4f);
                linePaint.setAlpha((int) (Math.min(1f, Math.max(0f, opacity)) * 255));
                canvas.drawLine(fromNode.x, fromNode.y, toNode.x, toNode.y, linePaint);
            }
        }

        // update and draw nodes
        for (Node node : nodes) {
            float mouseDx = node.x - mouseX;
            float mouseDy = node.y - mouseY;
            float mouseDist = (float) Math.sqrt(mouseDx * mouseDx + mouseDy * mouseDy);
            float mouseInfluence = Math.max(0, 1f - mouseDist / interactionRadius);

            // Repel from mouse
            if (mouseDist < interactionRadius && mouseDist > 0) {
                float force = (interactionRadius - mouseDist) / interactionRadius;
                node.vx += (mouseDx / mouseDist) * force * 0.2f;
                node.vy += (mouseDy / mouseDist) * force * 0.2f;
            }

            node.x += node.vx;
            node.y += node.vy;

            // Bounce off edges
            if (node.x < 0 || node.x > width) {
                node.vx *= -0.8f;
                node.x = Math.max(0, Math.min(width, node.x));
            }
            if (node.y < 0 || node.y > height) {
                node.vy *= -0.8f;
                node.y = Math.max(0, Math.min(height, node.y));
            }

            // Damping (friction)
            node.vx *= 0.98f;
            node.vy *= 0.98f;

            float nodeRadius = node.radius + mouseInfluence * 6f;
            float nodeOpacity = 0.5f + mouseInfluence * 0.5f;

            // Glow effect
            if (mouseInfluence > 0.2f && nodeRadius * 3 > 0) {
                int colorStart = Color.argb((int)(mouseInfluence * 0.4f * 255), 255, 218, 3);
                int colorMid = Color.argb((int)(mouseInfluence * 0.2f * 255), 255, 218, 3);
                int colorEnd = Color.argb(0, 255, 218, 3);
                RadialGradient gradient = new RadialGradient(node.x, node.y, nodeRadius * 3,
                        new int[]{colorStart, colorMid, colorEnd},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP);
                glowPaint.setShader(gradient);
                canvas.drawCircle(node.x, node.y, nodeRadius * 3, glowPaint);
            }

            nodePaint.setAlpha((int) (Math.min(1f, Math.max(0f, nodeOpacity)) * 255));
            canvas.drawCircle(node.x, node.y, nodeRadius, nodePaint);
        }

        invalidate(); // loop animation
    }
}
