package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Dedicated animated title screen; world initialization happens after selection. */
final class TitleScreen extends Canvas implements Runnable {
    private final IndevViewerMIDlet owner;
    private Image skybox, titleImage, panorama, scaledTitle;
    private int renderWidth=-1, renderHeight=-1, selected=0;
    private float panoramaOffset;
    private Thread animationThread;
    private boolean running;

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
        int bw=Math.min(w-24,210); if (bw<1) bw=w;
        int bh=Math.min(28,Math.max(22,h/10)), bx=(w-bw)/2;
        int y=Math.max(h*3/5,h/2);
        if (y+bh*2+7>h-24) y=Math.max(8,h-bh*2-30);
        drawButton(g,bx,y,bw,bh,"Singleplayer",selected==0);
        drawButton(g,bx,y+bh+7,bw,bh,"Quit Game",selected==1);
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
        if (action==UP || keyCode=='2') { selected=0; repaint(); return; }
        if (action==DOWN || keyCode=='8') { selected=1; repaint(); return; }
        if (action==LEFT || action==RIGHT || keyCode=='4' || keyCode=='6') { selected=1-selected; repaint(); return; }
        if (keyCode=='1') { owner.startGame(); return; }
        if (keyCode=='3') { stop(); owner.quitGame(); return; }
        if (action==FIRE || keyCode=='5' || keyCode==' ' || keyCode=='\n') {
            if (selected==0) owner.startGame(); else { stop(); owner.quitGame(); }
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
