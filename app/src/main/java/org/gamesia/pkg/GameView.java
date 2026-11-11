package org.gamesia.pkg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Random;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // Tipos de tile
    private static final int GRASS = 0, TALL = 1, TREE = 2, WATER = 3, PATH = 4, FLOWER = 5;
    private static final int MW = 48, MH = 36;

    private final int[][] map = new int[MH][MW];
    private final SurfaceHolder holder;
    private final Paint p = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Random rnd = new Random();

    private Thread thread;
    private volatile boolean running;

    // Jogador (posicao em unidades de tile, ponto central)
    private float px = 24.5f, py = 18.8f;
    private int dir = 0; // 0 baixo, 1 esquerda, 2 direita, 3 cima
    private float walkTime = 0f;
    private float stepAcc = 0f;

    // Entrada
    private volatile float stickX, stickY;
    private volatile boolean run;
    private volatile boolean aTap;
    private int stickId = -1, aId = -1, bId = -1;
    private float baseX, baseY, knobX, knobY;
    private boolean stickActive;

    // Layout
    private float screenW, screenH, stickR, btnR;
    private float aX, aY, bX, bY, defBaseX, defBaseY;
    private int tile = 48;

    // Mensagem
    private String banner = null;
    private float bannerT = 0f;

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        buildMap();
    }

    // ------------------------------------------------------------ mapa

    private void buildMap() {
        Random r = new Random(7);
        for (int y = 0; y < MH; y++) {
            for (int x = 0; x < MW; x++) {
                map[y][x] = (r.nextInt(14) == 0) ? FLOWER : GRASS;
            }
        }
        // gramas altas
        for (int i = 0; i < 16; i++) {
            int cx = 3 + r.nextInt(MW - 6), cy = 3 + r.nextInt(MH - 6), rad = 2 + r.nextInt(3);
            for (int y = cy - rad; y <= cy + rad; y++) {
                for (int x = cx - rad; x <= cx + rad; x++) {
                    if (x < 0 || y < 0 || x >= MW || y >= MH) continue;
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) map[y][x] = TALL;
                }
            }
        }
        // arvores espalhadas
        for (int y = 0; y < MH; y++) {
            for (int x = 0; x < MW; x++) {
                if (map[y][x] == GRASS && r.nextInt(22) == 0) map[y][x] = TREE;
            }
        }
        // lago
        for (int y = 4; y <= 11; y++) {
            for (int x = 30; x <= 43; x++) {
                float dx = (x - 36.5f) / 7f, dy = (y - 7.5f) / 4f;
                if (dx * dx + dy * dy <= 1f) map[y][x] = WATER;
            }
        }
        // caminhos (2 de largura)
        for (int x = 0; x < MW; x++) {
            map[18][x] = PATH;
            map[19][x] = PATH;
        }
        for (int y = 0; y < MH; y++) {
            map[y][24] = PATH;
            map[y][25] = PATH;
        }
        // borda de arvores
        for (int x = 0; x < MW; x++) {
            map[0][x] = TREE;
            map[MH - 1][x] = TREE;
        }
        for (int y = 0; y < MH; y++) {
            map[y][0] = TREE;
            map[y][MW - 1] = TREE;
        }
        // bordas dos caminhos ficam abertas (passagem)
        map[18][0] = PATH; map[19][0] = PATH;
        map[18][MW - 1] = PATH; map[19][MW - 1] = PATH;
        map[0][24] = PATH; map[0][25] = PATH;
        map[MH - 1][24] = PATH; map[MH - 1][25] = PATH;
    }

    private boolean solidAt(float x, float y) {
        int tx = (int) Math.floor(x), ty = (int) Math.floor(y);
        if (tx < 0 || ty < 0 || tx >= MW || ty >= MH) return true;
        int t = map[ty][tx];
        return t == TREE || t == WATER;
    }

    private boolean blocked(float x, float y) {
        float hw = 0.26f;
        return solidAt(x - hw, y) || solidAt(x + hw, y)
                || solidAt(x - hw, y + 0.3f) || solidAt(x + hw, y + 0.3f);
    }

    // ------------------------------------------------------------ ciclo de vida

    @Override
    public void surfaceCreated(SurfaceHolder h) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        screenW = w;
        screenH = hgt;
        tile = Math.max(16, Math.round(hgt / 8.5f));
        haze = new LinearGradient(0, 0, 0, hgt * 0.45f, 0xDD9ED8F2, 0x009ED8F2, Shader.TileMode.CLAMP);
        vig = new RadialGradient(w / 2f, hgt / 2f, Math.max(w, hgt) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        stickR = hgt * 0.17f;
        btnR = hgt * 0.09f;
        defBaseX = stickR * 1.7f;
        defBaseY = hgt - stickR * 1.7f;
        aX = w - hgt * 0.14f;
        aY = hgt - hgt * 0.32f;
        bX = w - hgt * 0.36f;
        bY = hgt - hgt * 0.15f;
        if (!stickActive) {
            baseX = defBaseX;
            baseY = defBaseY;
            knobX = baseX;
            knobY = baseY;
        }
        if (!running) {
            running = true;
            thread = new Thread(this, "game-loop");
            thread.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    update(dt);
                    drawGame(c);
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
            long sleep = 16 - (System.nanoTime() - now) / 1000000L;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ logica

    private void update(float dt) {
        float sx = stickX, sy = stickY;
        float mag = (float) Math.sqrt(sx * sx + sy * sy);
        if (mag > 0.12f) {
            float speed = (run ? 7.0f : 4.2f) * Math.min(1f, mag);
            float dx = (sx / mag) * speed * dt;
            float dy = (sy / mag) * speed * dt;
            if (!blocked(px + dx, py)) px += dx;
            if (!blocked(px, py + dy)) py += dy;
            if (Math.abs(sx) > Math.abs(sy)) dir = sx < 0 ? 1 : 2;
            else dir = sy < 0 ? 3 : 0;
            walkTime += dt * (run ? 14f : 9f) * Math.min(1f, mag + 0.3f);

            int tx = (int) Math.floor(px), ty = (int) Math.floor(py + 0.15f);
            if (tx >= 0 && ty >= 0 && tx < MW && ty < MH && map[ty][tx] == TALL) {
                stepAcc += speed * dt;
                if (stepAcc >= 0.7f) {
                    stepAcc = 0f;
                    if (rnd.nextInt(100) < 12) showBanner("Um Pokémon selvagem apareceu!");
                }
            } else {
                stepAcc = 0f;
            }
        } else {
            walkTime = 0f;
        }

        camX += (px - camX) * Math.min(1f, dt * 8f);
        camY += (py - camY) * Math.min(1f, dt * 8f);
        camX = clamp(camX, 5f, MW - 5f);
        camY = clamp(camY, 3.5f, MH - 3f);

        if (aTap) {
            aTap = false;
            interact();
        }
        if (bannerT > 0f) {
            bannerT -= dt;
            if (bannerT <= 0f) banner = null;
        }
    }

    private void interact() {
        float fx = px, fy = py + 0.15f;
        if (dir == 0) fy += 0.9f;
        else if (dir == 3) fy -= 0.9f;
        else if (dir == 1) fx -= 0.9f;
        else fx += 0.9f;
        int tx = (int) Math.floor(fx), ty = (int) Math.floor(fy);
        String msg = "...";
        if (tx >= 0 && ty >= 0 && tx < MW && ty < MH) {
            switch (map[ty][tx]) {
                case TREE: msg = "Uma árvore grande e antiga."; break;
                case WATER: msg = "A água é cristalina."; break;
                case FLOWER: msg = "Que flor bonita!"; break;
                case TALL: msg = "Pode haver Pokémon na grama alta..."; break;
                default: msg = "Bem-vindo a Pokémon Gerações!"; break;
            }
        }
        showBanner(msg);
    }

    private void showBanner(String s) {
        banner = s;
        bannerT = 2.5f;
    }

    // ------------------------------------------------------------ desenho (mundo 3D + sprites 2D)

    private static final float K = 8f, TILT = 0.8f;
    private float camX = 24.5f, camY = 18.8f, cxS, cyS;
    private final Paint fx = new Paint();
    private LinearGradient haze;
    private RadialGradient vig;

    // escala de perspectiva (perto = maior, longe = menor)
    private float sc(float dy) {
        return K / (K - 0.45f * dy);
    }

    // y de tela de uma linha do chao
    private float gy(float dy) {
        return cyS + dy * tile * TILT * sc(dy);
    }

    private void drawGame(Canvas c) {
        c.drawColor(0xFF2E5E3A);
        cxS = screenW / 2f;
        cyS = screenH * 0.58f;
        int yA = Math.max(0, (int) Math.floor(camY) - 9);
        int yB = Math.min(MH - 1, (int) Math.floor(camY) + 5);
        long time = System.currentTimeMillis();
        int pr = (int) Math.floor(py + 0.3f);

        // 1) chao em perspectiva
        for (int y = yA; y <= yB; y++) {
            float top = gy(y - camY), bot = gy(y + 1 - camY);
            if (bot < 0 || top > screenH) continue;
            float tw = tile * sc(y + 0.5f - camY);
            int xA = Math.max(0, (int) Math.floor(camX - cxS / tw) - 1);
            int xB = Math.min(MW - 1, (int) Math.ceil(camX + (screenW - cxS) / tw) + 1);
            for (int x = xA; x <= xB; x++) {
                float l = cxS + (x - camX) * tw;
                drawGround(c, map[y][x], x, y, l, top, l + tw, bot, time);
            }
        }

        // 2) objetos em pe (sprites 2D) + jogador, de tras para frente
        for (int y = yA; y <= yB; y++) {
            float top = gy(y - camY), bot = gy(y + 1 - camY);
            if (!(bot < -tile || top > screenH + tile * 2f)) {
                float tw = tile * sc(y + 0.5f - camY);
                int xA = Math.max(0, (int) Math.floor(camX - cxS / tw) - 2);
                int xB = Math.min(MW - 1, (int) Math.ceil(camX + (screenW - cxS) / tw) + 2);
                for (int x = xA; x <= xB; x++) {
                    int t = map[y][x];
                    if (t == TREE || t == TALL || t == FLOWER) {
                        float l = cxS + (x - camX) * tw;
                        drawObject(c, t, x, y, l, top, l + tw, bot);
                    }
                }
            }
            if (y == pr) drawPlayerAt(c, pr);
        }

        // 3) neblina no horizonte + vinheta
        fx.setStyle(Paint.Style.FILL);
        if (haze != null) {
            fx.setShader(haze);
            c.drawRect(0, 0, screenW, screenH * 0.45f, fx);
        }
        if (vig != null) {
            fx.setShader(vig);
            c.drawRect(0, 0, screenW, screenH, fx);
        }
        fx.setShader(null);

        drawHud(c);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void fill(Canvas c, float x, float y, float w, float h, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRect(x, y, x + w, y + h, p);
    }

    private void drawGround(Canvas c, int t, int tx, int ty, float l, float tp, float r, float b, long time) {
        float ux = (r - l) / 16f, uy = (b - tp) / 16f;
        int h = (tx * 73856093) ^ (ty * 19349663);
        int v = (h >>> 3) & 3;
        float w = r - l + 1f, hh = b - tp + 1f;
        switch (t) {
            case TALL:
                fill(c, l, tp, w, hh, 0xFF3F9E4A);
                break;
            case TREE:
                fill(c, l, tp, w, hh, 0xFF4C9A4B);
                break;
            case WATER: {
                fill(c, l, tp, w, hh, 0xFF3A8EDB);
                float off = (float) Math.sin((time / 400.0) + tx * 0.7 + ty * 0.4) * 2f * ux;
                fill(c, l + 3 * ux + off, tp + 5 * uy, 5 * ux, 1.2f * uy + 0.5f, 0xFF7CC1F2);
                fill(c, l + 8 * ux - off, tp + 11 * uy, 5 * ux, 1.2f * uy + 0.5f, 0xFF7CC1F2);
                break;
            }
            case PATH:
                fill(c, l, tp, w, hh, 0xFFD9BC82);
                fill(c, l + (3 + v * 3) * ux, tp + 4 * uy, 1.6f * ux, 1.6f * uy + 0.5f, 0xFFBB9A62);
                fill(c, l + (10 - v) * ux, tp + 10 * uy, 1.6f * ux, 1.6f * uy + 0.5f, 0xFFE8D2A4);
                break;
            default:
                fill(c, l, tp, w, hh, v == 0 ? 0xFF5AB257 : 0xFF5DB85A);
                if (v == 1) fill(c, l + 5 * ux, tp + 9 * uy, 1.5f * ux, 3 * uy, 0xFF4A9E4A);
                if (v == 2) fill(c, l + 11 * ux, tp + 4 * uy, 1.5f * ux, 3 * uy, 0xFF4A9E4A);
                break;
        }
    }

    private void drawObject(Canvas c, int t, int tx, int ty, float l, float tp, float r, float b) {
        float ux = (r - l) / 16f;
        int h = (tx * 73856093) ^ (ty * 19349663);
        int v = (h >>> 3) & 3;
        switch (t) {
            case TREE:
                drawTree(c, (l + r) / 2f, tp + (b - tp) * 0.7f, ux);
                break;
            case TALL:
                for (int i = 0; i < 6; i++) {
                    float bx = l + (1f + i * 2.6f + ((h >>> (i + 2)) & 1)) * ux;
                    float base = tp + (b - tp) * (0.45f + 0.4f * (i % 2));
                    fill(c, bx, base - 8 * ux, 1.6f * ux, 8 * ux, 0xFF2C7C38);
                    fill(c, bx + 1.6f * ux, base - 6.5f * ux, 1.2f * ux, 6 * ux, 0xFF6BCB6E);
                }
                break;
            case FLOWER: {
                int col = (v < 2) ? 0xFFFFE066 : 0xFFFF7BAC;
                float x1 = l + 4 * ux, y1 = tp + (b - tp) * 0.45f;
                fill(c, x1 + 0.6f * ux, y1 + 2 * ux, 0.8f * ux, 3 * ux, 0xFF2E7D32);
                fill(c, x1, y1, 2 * ux, 2 * ux, col);
                float x2 = l + 10 * ux, y2 = tp + (b - tp) * 0.75f;
                fill(c, x2 + 0.6f * ux, y2 + 2 * ux, 0.8f * ux, 3 * ux, 0xFF2E7D32);
                fill(c, x2, y2, 2 * ux, 2 * ux, 0xFFFFFFFF);
                break;
            }
            default:
                break;
        }
    }

    private void drawTree(Canvas c, float bx, float by, float u) {
        p.setStyle(Paint.Style.FILL);
        // sombra no chao
        p.setColor(0x33000000);
        rf.set(bx - 7 * u, by - 2 * u, bx + 11 * u, by + 3 * u);
        c.drawOval(rf, p);
        // tronco
        fill(c, bx - 2 * u, by - 10 * u, 4 * u, 10 * u, 0xFF6B4423);
        fill(c, bx - 2 * u, by - 10 * u, 1.2f * u, 10 * u, 0xFF8A5A30);
        // copa
        p.setColor(0xFF1F6B33);
        c.drawCircle(bx, by - 20 * u, 9 * u, p);
        p.setColor(0xFF267A3B);
        c.drawCircle(bx + 3 * u, by - 16 * u, 6 * u, p);
        p.setColor(0xFF2F8F45);
        c.drawCircle(bx - 1.5f * u, by - 22 * u, 7 * u, p);
        p.setColor(0xFF58B864);
        c.drawCircle(bx - 3.5f * u, by - 25 * u, 2.8f * u, p);
    }

    private void drawPlayerAt(Canvas c, int pr) {
        float dy = py + 0.3f - camY;
        float s = sc(dy);
        float fpx = cxS + (px - camX) * tile * s;
        float fpy = gy(dy);
        float u = tile * s / 14f;
        drawPlayer(c, fpx, fpy - 6f * u, u);

        // grama alta na frente das pernas
        int ptx = (int) Math.floor(px);
        if (ptx >= 0 && ptx < MW && pr >= 0 && pr < MH && map[pr][ptx] == TALL) {
            float tw = tile * sc(pr + 0.5f - camY);
            float l = cxS + (ptx - camX) * tw;
            drawObject(c, TALL, ptx, pr, l, gy(pr - camY), l + tw, gy(pr + 1 - camY));
        }
    }

    private void drawPlayer(Canvas c, float cx, float cy, float u) {
        boolean moving = walkTime > 0f;
        float swing = moving ? (float) Math.sin(walkTime) : 0f;
        float bob = moving ? Math.abs((float) Math.sin(walkTime)) * 1.2f * u : 0f;

        // sombra
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x44000000);
        rf.set(cx - 6 * u, cy + 3 * u, cx + 6 * u, cy + 8 * u);
        c.drawOval(rf, p);

        float by = cy - bob;
        // pernas
        fill(c, cx - 3.5f * u, by + 2 * u + swing * 1.2f * u, 3 * u, 5 * u, 0xFF243A6B);
        fill(c, cx + 0.5f * u, by + 2 * u - swing * 1.2f * u, 3 * u, 5 * u, 0xFF243A6B);
        // sapatos
        fill(c, cx - 3.5f * u, by + 6 * u + swing * 1.2f * u, 3 * u, 1.5f * u, 0xFF222222);
        fill(c, cx + 0.5f * u, by + 6 * u - swing * 1.2f * u, 3 * u, 1.5f * u, 0xFF222222);
        // corpo
        fill(c, cx - 4.5f * u, by - 3 * u, 9 * u, 6 * u, 0xFFD93636);
        // bracos
        float armSwing = swing * 1.5f * u;
        if (dir == 1 || dir == 2) {
            fill(c, cx - 1.5f * u, by - 2.5f * u + armSwing, 3 * u, 5 * u, 0xFFB92B2B);
        } else {
            fill(c, cx - 6 * u, by - 2.5f * u + armSwing, 2 * u, 5 * u, 0xFFB92B2B);
            fill(c, cx + 4 * u, by - 2.5f * u - armSwing, 2 * u, 5 * u, 0xFFB92B2B);
        }
        // cabeca
        fill(c, cx - 4 * u, by - 9 * u, 8 * u, 7 * u, 0xFFFFD3A8);
        // bone
        fill(c, cx - 4.5f * u, by - 10.5f * u, 9 * u, 4 * u, 0xFFE02B2B);
        if (dir == 0) {
            fill(c, cx - 4.5f * u, by - 6.8f * u, 9 * u, 1.2f * u, 0xFFFFFFFF);
            fill(c, cx - 2.5f * u, by - 4.5f * u, 1.4f * u, 1.8f * u, 0xFF222222);
            fill(c, cx + 1.1f * u, by - 4.5f * u, 1.4f * u, 1.8f * u, 0xFF222222);
        } else if (dir == 3) {
            fill(c, cx - 4 * u, by - 7 * u, 8 * u, 4.5f * u, 0xFF5A3A1E);
            fill(c, cx - 4.5f * u, by - 10.5f * u, 9 * u, 5 * u, 0xFFE02B2B);
        } else if (dir == 1) {
            fill(c, cx - 6.5f * u, by - 6.8f * u, 4 * u, 1.2f * u, 0xFFFFFFFF);
            fill(c, cx - 3 * u, by - 4.5f * u, 1.4f * u, 1.8f * u, 0xFF222222);
            fill(c, cx + 1 * u, by - 7 * u, 3 * u, 3 * u, 0xFF5A3A1E);
        } else {
            fill(c, cx + 2.5f * u, by - 6.8f * u, 4 * u, 1.2f * u, 0xFFFFFFFF);
            fill(c, cx + 1.6f * u, by - 4.5f * u, 1.4f * u, 1.8f * u, 0xFF222222);
            fill(c, cx - 4 * u, by - 7 * u, 3 * u, 3 * u, 0xFF5A3A1E);
        }
    }

    private void drawHud(Canvas c) {
        // joystick analogico
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000);
        c.drawCircle(baseX, baseY, stickR, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(screenH * 0.006f);
        p.setColor(0x88FFFFFF);
        c.drawCircle(baseX, baseY, stickR, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(stickActive ? 0xAAFFFFFF : 0x66FFFFFF);
        c.drawCircle(knobX, knobY, stickR * 0.45f, p);

        // botoes A e B
        drawButton(c, aX, aY, "A", 0xAAE53935);
        drawButton(c, bX, bY, "B", run ? 0xCCFFB300 : 0xAA1E88E5);

        // mensagem
        if (banner != null) {
            text.setTextSize(screenH * 0.055f);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            float tw = text.measureText(banner);
            float bw = tw + screenH * 0.12f, bh = screenH * 0.13f;
            float bx = screenW / 2f, byy = screenH * 0.04f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xDD111827);
            rf.set(bx - bw / 2f, byy, bx + bw / 2f, byy + bh);
            c.drawRoundRect(rf, bh * 0.25f, bh * 0.25f, p);
            c.drawText(banner, bx, byy + bh * 0.66f, text);
        }
    }

    private void drawButton(Canvas c, float x, float y, String label, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x, y, btnR, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(screenH * 0.006f);
        p.setColor(0xCCFFFFFF);
        c.drawCircle(x, y, btnR, p);
        text.setTextSize(btnR * 0.9f);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, x, y + btnR * 0.32f, text);
    }

    // ------------------------------------------------------------ toque

    private boolean inCircle(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private void moveStick(float x, float y) {
        float dx = x - baseX, dy = y - baseY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > stickR) {
            dx = dx / d * stickR;
            dy = dy / d * stickR;
        }
        knobX = baseX + dx;
        knobY = baseY + dy;
        stickX = dx / stickR;
        stickY = dy / stickR;
    }

    private void releaseStick() {
        stickId = -1;
        stickActive = false;
        stickX = 0f;
        stickY = 0f;
        baseX = defBaseX;
        baseY = defBaseY;
        knobX = baseX;
        knobY = baseY;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                if (aId == -1 && inCircle(x, y, aX, aY, btnR * 1.3f)) {
                    aId = id;
                    aTap = true;
                } else if (bId == -1 && inCircle(x, y, bX, bY, btnR * 1.3f)) {
                    bId = id;
                    run = true;
                } else if (stickId == -1 && x < screenW * 0.5f) {
                    stickId = id;
                    stickActive = true;
                    baseX = x;
                    baseY = y;
                    moveStick(x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == stickId) moveStick(e.getX(i), e.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id == stickId) releaseStick();
                if (id == aId) aId = -1;
                if (id == bId) {
                    bId = -1;
                    run = false;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                releaseStick();
                aId = -1;
                bId = -1;
                run = false;
                break;
            }
            default:
                break;
        }
        return true;
    }
}
