package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Animated title screen with a compact settings page. */
final class TitleScreen extends Canvas implements Runnable {
    private final IndevViewerMIDlet owner;
    private Image skybox, titleImage, panorama, scaledTitle;
    private int renderWidth=-1, renderHeight=-1, selected=0;
    private float panoramaOffset;
    private Thread animationThread;
    private boolean running, settingsOpen, controlsOpen, waitingForBinding;
    private int controlSelection = 0;

    TitleScreen(IndevViewerMIDlet midlet) {
        owner=midlet;
        setFullScreenMode(true);
        try { skybox=Image.createImage("/mcj2me/skybox.png"); titleImage=Image.createImage("/mcj2me/title.png"); }
        catch (Exception e) { /* Menu remains usable if an image is missing. */ }
    }
    synchronized void start() {
        if (running) return;
        running=true; animationThread=new Thread(this); animationThread.start();
    }
    synchronized void stop() {
        running=false;
        if (animationThread!=null) { animationThread.interrupt(); animationThread=null; }
    }
    public void run() {
        while (running) {
            if (panorama!=null) {
                panoramaOffset+=0.35f;
                int max=Math.max(0,panorama.getWidth()-Math.max(1,getWidth()));
                if (panoramaOffset>max) panoramaOffset=0;
            }
            repaint(); serviceRepaints();
            try { Thread.sleep(40); } catch (InterruptedException e) { /* loop checks running */ }
        }
    }
    protected void paint(Graphics g) {
        int w=Math.max(1,getWidth()), h=Math.max(1,getHeight());
        if (renderWidth!=w || renderHeight!=h || panorama==null) {
            renderWidth=w; renderHeight=h;
            if (skybox!=null) panorama=resizeImage(skybox,w*2,h,true);
            if (titleImage!=null) {
                int maxW=Math.max(1,Math.min(w-20,512)), maxH=Math.max(1,Math.min(h/3,128));
                float scale=Math.min((float)maxW/titleImage.getWidth(),(float)maxH/titleImage.getHeight());
                scaledTitle=resizeImage(titleImage,Math.max(1,(int)(titleImage.getWidth()*scale)),
                    Math.max(1,(int)(titleImage.getHeight()*scale)),false);
            }
            panoramaOffset=0;
        }
        if (panorama!=null) {
            int px=Math.min(Math.max(0,panorama.getWidth()-w),(int)panoramaOffset);
            g.drawRegion(panorama,px,0,w,h,0,0,0,Graphics.TOP|Graphics.LEFT);
        } else { g.setColor(0x87BFEA); g.fillRect(0,0,w,h); }
        if (scaledTitle!=null) g.drawImage(scaledTitle,(w-scaledTitle.getWidth())/2,
            Math.max(8,h/10),Graphics.TOP|Graphics.LEFT);
        else { g.setColor(0xFFFFFF); g.drawString("Minecraft",w/2,Math.max(8,h/5),Graphics.TOP|Graphics.HCENTER); }

        if (controlsOpen) {
            g.setColor(0xFFFFFF);
            g.drawString("CUSTOM CONTROLS", w/2, h/3-24, Graphics.TOP|Graphics.HCENTER);
            int visible = 5;
            int first = controlSelection - 2;
            if (first < 0) first = 0;
            if (first > IndevViewerMIDlet.CONTROL_COUNT-visible)
                first = IndevViewerMIDlet.CONTROL_COUNT-visible;
            int rowY = h/3;
            for (int n = first; n < first+visible; n++) {
                int y = rowY + (n-first)*23;
                String line = IndevViewerMIDlet.CONTROL_NAMES[n] + ": " +
                    IndevViewerMIDlet.controlKeyName(IndevViewerMIDlet.controlKeys[n]);
                drawButton(g, 8, y, w-16, 21, line, n==controlSelection);
            }
            String help = waitingForBinding ? "Press a key to bind..." : "FIRE: remap  *: back";
            g.setColor(0xFFFFFF);
            g.drawString(help, w/2, h-38, Graphics.BOTTOM|Graphics.HCENTER);
            g.drawString("UP/DOWN: choose control", w/2, h-20, Graphics.BOTTOM|Graphics.HCENTER);
        } else if (settingsOpen) {
            drawButton(g, 12, h/2-54, w-24, 27,
                "Sensitivity: " + IndevViewerMIDlet.sensitivity, selected==0);
            String fog = IndevViewerMIDlet.fogLevel==0 ? "Near" :
                (IndevViewerMIDlet.fogLevel==2 ? "Far" : "Normal");
            drawButton(g, 12, h/2-22, w-24, 27, "Fog: " + fog, selected==1);
            drawButton(g, 12, h/2+10, w-24, 27, "Controls", selected==2);
            drawButton(g, 12, h/2+42, w-24, 27, "Back", selected==3);
            g.setColor(0xFFFFFF);
            g.drawString("LEFT/RIGHT: change", w/2, h-43, Graphics.BOTTOM|Graphics.HCENTER);
            g.drawString("UP/DOWN: select  FIRE: open", w/2, h-25, Graphics.BOTTOM|Graphics.HCENTER);
        } else {
            int bw=Math.min(w-24,210); if (bw<1) bw=w;
            int bh=Math.min(27,Math.max(20,h/12)), bx=(w-bw)/2;
            int total=bh*3+14;
            int y=Math.max(h*3/5,h/2);
            if (y+total>h-24) y=Math.max(8,h-total-28);
            drawButton(g,bx,y,bw,bh,"Singleplayer",selected==0);
            drawButton(g,bx,y+bh+7,bw,bh,"Settings",selected==1);
            drawButton(g,bx,y+(bh+7)*2,bw,bh,"Quit Game",selected==2);
        }
        g.setColor(0xFFFFFF); g.drawString("Minecraft J2ME Edition",5,h-17,Graphics.BOTTOM|Graphics.LEFT);
        g.setColor(0xFFFF55); g.drawString("Indev demake",w-5,h-17,Graphics.BOTTOM|Graphics.RIGHT);
    }
    private void drawButton(Graphics g,int x,int y,int w,int h,String label,boolean active) {
        g.setColor(active?0x777777:0x555555); g.fillRect(x,y,w,h);
        g.setColor(active?0xFFFFAA:0xAAAAAA); g.drawRect(x,y,w-1,h-1);
        g.setColor(0x222222); g.drawLine(x+1,y+h-2,x+w-2,y+h-2);
        Font f=Font.getDefaultFont(); g.setColor(active?0xFFFF55:0xFFFFFF);
        g.drawString(label,x+(w-f.stringWidth(label))/2,y+(h-f.getHeight())/2,Graphics.TOP|Graphics.LEFT);
    }
    protected void keyPressed(int keyCode) {
        int action=getGameAction(keyCode);
        if (controlsOpen) {
            if (waitingForBinding) {
                int direction = IndevViewerMIDlet.directionalKey(keyCode, action);
                if (direction >= 0) IndevViewerMIDlet.controlKeys[controlSelection] = direction;
                else if (keyCode != '*' && keyCode != 42 && keyCode != '#' && keyCode != 35)
                    IndevViewerMIDlet.controlKeys[controlSelection] = keyCode;
                IndevViewerMIDlet.saveControls();
                waitingForBinding=false;
            } else if (action==UP || keyCode=='2') {
                controlSelection=(controlSelection+IndevViewerMIDlet.CONTROL_COUNT-1)%IndevViewerMIDlet.CONTROL_COUNT;
            } else if (action==DOWN || keyCode=='8') {
                controlSelection=(controlSelection+1)%IndevViewerMIDlet.CONTROL_COUNT;
            } else if (action==FIRE || keyCode=='5' || keyCode==' ' || keyCode=='\n') {
                waitingForBinding=true;
            } else if (keyCode=='*' || keyCode==42) {
                controlsOpen=false; settingsOpen=true; selected=2;
            }
            repaint();
            return;
        }
        if (settingsOpen) {
            if (action==UP || keyCode=='2') selected=(selected+3)%4;
            else if (action==DOWN || keyCode=='8') selected=(selected+1)%4;
            else if (action==LEFT || keyCode=='4') {
                if (selected==0 && IndevViewerMIDlet.sensitivity>1) IndevViewerMIDlet.sensitivity--;
                else if (selected==1 && IndevViewerMIDlet.fogLevel>0) IndevViewerMIDlet.fogLevel--;
            } else if (action==RIGHT || keyCode=='6') {
                if (selected==0 && IndevViewerMIDlet.sensitivity<6) IndevViewerMIDlet.sensitivity++;
                else if (selected==1 && IndevViewerMIDlet.fogLevel<2) IndevViewerMIDlet.fogLevel++;
            } else if (action==FIRE || keyCode=='5' || keyCode==' ' || keyCode=='\n') {
                if (selected==2) { controlsOpen=true; controlSelection=0; waitingForBinding=false; }
                else if (selected==3) { settingsOpen=false; selected=1; }
            } else if (keyCode=='*' || keyCode==42) { settingsOpen=false; selected=1; }
            repaint();
            return;
        }
        if (action==UP || action==DOWN || keyCode=='2' || keyCode=='8') {
            selected=(selected+(action==UP || keyCode=='2' ? 2 : 1))%3; repaint(); return;
        }
        if (action==LEFT || action==RIGHT || keyCode=='4' || keyCode=='6') {
            selected=(selected+1)%3; repaint(); return;
        }
        if (action==FIRE || keyCode=='5' || keyCode==' ' || keyCode=='\n') {
            if (selected==0) owner.startGame();
            else if (selected==1) { settingsOpen=true; selected=0; repaint(); }
            else { stop(); owner.quitGame(); }
        }
    }
    private Image resizeImage(Image source,int targetW,int targetH,boolean cover) {
        int sw=source.getWidth(), sh=source.getHeight(), cropX=0,cropY=0,cropW=sw,cropH=sh;
        if (cover) {
            long lhs=(long)sw*targetH, rhs=(long)sh*targetW;
            if (lhs>rhs) { cropW=(int)((long)sh*targetW/targetH); cropX=(sw-cropW)/2; }
            else if (lhs<rhs) { cropH=(int)((long)sw*targetH/targetW); cropY=(sh-cropH)/2; }
        }
        int[] row=new int[sw], pixels=new int[targetW*targetH];
        for (int y=0;y<targetH;y++) {
            int sy=cropY+(int)((long)y*cropH/targetH);
            source.getRGB(row,0,sw,0,sy,sw,1);
            int dest=y*targetW;
            for (int x=0;x<targetW;x++) pixels[dest+x]=row[cropX+(int)((long)x*cropW/targetW)];
        }
        return Image.createRGBImage(pixels,targetW,targetH,true);
    }
}
