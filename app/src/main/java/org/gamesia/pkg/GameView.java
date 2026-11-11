package org.gamesia.pkg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
        tile = Math.max(16, Math.round(hgt / 9f));
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

    // ------------------------------------------------------------ desenho

    private void drawGame(Canvas c) {
        c.drawColor(Color.BLACK);
        float camX = clamp(px * tile - screenW / 2f, 0f, Math.max(0f, MW * tile - screenW));
        float camY = clamp(py * tile - screenH / 2f, 0f, Math.max(0f, MH * tile - screenH));
        int x0 = Math.max(0, (int) (camX / tile));
        int y0 = Math.max(0, (int) (camY / tile));
        int x1 = Math.min(MW - 1, (int) ((camX + screenW) / tile));
        int y1 = Math.min(MH - 1, (int) ((camY + screenH) / tile));
        long time = System.currentTimeMillis();

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                drawTile(c, map[y][x], x, y, x * tile - camX, y * tile - camY, time);
            }
        }
        drawPlayer(c, px * tile - camX, py * tile - camY);
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

    private void drawTile(Canvas c, int t, int tx, int ty, float sx, float sy, long time) {
        float u = tile / 16f;
        int h = (tx * 73856093) ^ (ty * 19349663);
        int v = (h >>> 3) & 3;
        float s = tile + 1f;
        switch (t) {
            case TALL: {
                fill(c, sx, sy, s, s, 0xFF3F9E4A);
                for (int i = 0; i < 4; i++) {
                    float bx = sx + (2 + i * 4 + ((h >>> (i + 2)) & 1)) * u;
                    float by = sy + (4 + (i % 2) * 6) * u;
                    fill(c, bx, by, 1.5f * u, 6 * u, 0xFF2C7C38);
                    fill(c, bx + 1.5f * u, by + 1.5f * u, 1.2f * u, 4.5f * u, 0xFF6BCB6E);
                }
                break;
            }
            case TREE: {
                fill(c, sx, sy, s, s, 0xFF5DB85A);
                fill(c, sx + 6 * u, sy + 10 * u, 4 * u, 6 * u, 0xFF6B4423);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF1F6B33);
                c.drawCircle(sx + 8 * u, sy + 7 * u, 7.5f * u, p);
                p.setColor(0xFF2F8F45);
                c.drawCircle(sx + 7 * u, sy + 6 * u, 5.5f * u, p);
                p.setColor(0xFF58B864);
                c.drawCircle(sx + 5.5f * u, sy + 4.5f * u, 2.2f * u, p);
                break;
            }
            case WATER: {
                fill(c, sx, sy, s, s, 0xFF3A8EDB);
                float off = (float) Math.sin((time / 400.0) + tx * 0.7 + ty * 0.4) * 2f * u;
                fill(c, sx + (3 * u) + off, sy + 5 * u, 5 * u, 1.2f * u, 0xFF7CC1F2);
                fill(c, sx + (8 * u) - off, sy + 11 * u, 5 * u, 1.2f * u, 0xFF7CC1F2);
                break;
            }
            case PATH: {
                fill(c, sx, sy, s, s, 0xFFD9BC82);
                fill(c, sx + (3 + v * 3) * u, sy + 4 * u, 1.6f * u, 1.6f * u, 0xFFBB9A62);
                fill(c, sx + (10 - v) * u, sy + 10 * u, 1.6f * u, 1.6f * u, 0xFFE8D2A4);
                break;
            }
            case FLOWER: {
                fill(c, sx, sy, s, s, 0xFF5DB85A);
                int col = (v < 2) ? 0xFFFFE066 : 0xFFFF7BAC;
                fill(c, sx + 4 * u, sy + 6 * u, 2 * u, 2 * u, col);
                fill(c, sx + 10 * u, sy + 10 * u, 2 * u, 2 * u, 0xFFFFFFFF);
                break;
            }
            default: {
                fill(c, sx, sy, s, s, v == 0 ? 0xFF5AB257 : 0xFF5DB85A);
                if (v == 1) fill(c, sx + 5 * u, sy + 9 * u, 1.5f * u, 3 * u, 0xFF4A9E4A);
                if (v == 2) fill(c, sx + 11 * u, sy + 4 * u, 1.5f * u, 3 * u, 0xFF4A9E4A);
                break;
            }
        }
    }

    private void drawPlayer(Canvas c, float cx, float cy) {
        float u = tile / 16f;
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
