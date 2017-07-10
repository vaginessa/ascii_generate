/*
 * Copyright (c) 2017 by Tran Le Duy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duy.ascii.sharedcode.image.converter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Creates Bitmaps and HTML from AsciiConverter.Result objects.
 */
public class AsciiRenderer {

    private static final boolean DEBUG = false;
    private static boolean nativeCodeAvailable = false;

    static {
        try {
            System.loadLibrary("asciiart");
            nativeCodeAvailable = true;
        } catch (Throwable ignored) {
        }
    }

    // One element of this array holds the visible bitmap. The next image is drawn offscreen into
    // the other element, and then activeBitmapIndex is flipped to make it visible.
    private Bitmap[] bitmaps = new Bitmap[2];
    private int activeBitmapIndex;
    // When rendering an ASCII image, we draw color values directly into an int array a row at a
    // time. We use a bitmap containing the possible characters and copy slices from it. This is
    // faster than Canvas.drawText.
    // We store some temporary objects used to fill the bitmap pixels between requests,
    // so we can avoid allocating new objects when possible. See drawIntoBitmap().
    private Bitmap possibleCharsBitmap;
    private int[] possibleCharsBitmapPixels;
    private byte[] possibleCharsGrayscale;
    private ExecutorService threadPool;
    private List<Worker> renderWorkers;
    private int maxWidth;
    private int maxHeight;
    private int outputImageWidth;
    private int outputImageHeight;
    private Paint paint = new Paint();

    private float charPixelHeight = 4.5f;
    private float charPixelWidth = 3.5f;
    private int textSize = 10;

    public Bitmap getVisibleBitmap() {
        return bitmaps[activeBitmapIndex];
    }

    public float getCharPixelHeight() {
        return charPixelHeight;
    }

    public float getCharPixelWidth() {
        return charPixelWidth;
    }

    public void setMaximumImageSize(int maxWidth, int maxHeight) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    public void setCameraImageSize(int width, int height) {
        float cameraRatio = ((float) width) / height;
        float viewRatio = ((float) this.maxWidth) / this.maxHeight;
        if (cameraRatio < viewRatio) {
            // camera preview is narrower than view, scale to full height
            this.outputImageHeight = this.maxHeight;
            this.outputImageWidth = (int) (this.outputImageHeight * cameraRatio);
        } else {
            this.outputImageWidth = this.maxWidth;
            this.outputImageHeight = (int) (this.maxWidth / cameraRatio);
        }
        // Scale 10 point text per 1000px width. Char width is 70% of text size and height is 90%.
        textSize = (int) Math.round(Math.max(10, outputImageWidth / 100.0));
        charPixelWidth = (textSize * 0.7f);
        charPixelHeight = (textSize * 0.9f);
    }

    public int getOutputImageWidth() {
        return this.outputImageWidth;
    }

    public int getOutputImageHeight() {
        return this.outputImageHeight;
    }

    public int asciiColumnsForWidth(int width) {
        return (int) (width / getCharPixelWidth());
    }

    public int asciiRowsForHeight(int height) {
        return (int) (height / getCharPixelHeight());
    }

    public int asciiRows() {
        return asciiRowsForHeight(this.outputImageHeight);
    }

    public int asciiColumns() {
        return asciiColumnsForWidth(this.outputImageWidth);
    }

    void initRenderThreadPool(int numThreads) {
        if (threadPool != null) {
            threadPool.shutdown();
        }
        if (numThreads <= 0) numThreads = Runtime.getRuntime().availableProcessors();
        threadPool = Executors.newFixedThreadPool(numThreads);
        renderWorkers = new ArrayList<Worker>();
        for (int i = 0; i < numThreads; i++) {
            renderWorkers.add(new Worker());
        }
    }

    public void destroyThreadPool() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    private void drawIntoBitmap(AsciiConverter.Result result, Bitmap bitmap) {
        paint.setARGB(255, 255, 255, 255);

        long t1 = System.nanoTime();
        // Directly drawing characters into the bitmap takes ~210ms on a Nexus 5x, and worse on a
        // Nexus 7. This results in a very choppy display.
        /*
        Canvas canvas = new Canvas(bitmap);
        canvas.drawARGB(255, 0, 0, 0);
        paint.setTextSize(textSize);
        if (result!=null) {
            for(int r=0; r<result.rows; r++) {
                int y = charPixelHeight * (r+1);  // Because drawText uses baseline. Should be -1?
                int x = 0;
                for(int c=0; c<result.columns; c++) {
                    String s = result.stringAtRowColumn(r, c);
                    paint.setColor(result.colorAtRowColumn(r, c));
                    canvas.drawText(s, x, y, paint);
                    x += charPixelWidth;
                }
            }
        }
        */

        // Instead, we directly generate the pixels a row of text at a time. We create a "template"
        // bitmap into which we draw one copy of each character that we might need, and convert
        // that to a flattened grayscale array. (Currently we only care whether the pixel has a
        // nonzero brightness, so no anti-aliasing support). Then for each character we want to
        // draw to the output image, we copy the corresponding pixels from the template bitmap.
        // (Setting the output image pixel to the color determined by AsciiCoverter if nonblack).
        //
        // This isn't much faster in Java (190ms on a Nexus 5x), but when implemented in C with
        // JNI, it drops to 55ms for an almost 4x performance increase on a single thread.
        // With 6 threads (as reported by Runtime.getAvailableProcessors), it's 20-25ms.

        // Create a bitmap containing each character that we might need to render. We could try to
        // skip this step if (as is usually the case) the characters are the same as the previous
        // frame, but in practice there's only a few characters and it takes almost no time.
        int pixelsPerRow = Math.min((int) (charPixelWidth * result.columns), 1);
        if (possibleCharsBitmap == null ||
                possibleCharsBitmap.getWidth() != pixelsPerRow ||
                possibleCharsBitmap.getHeight() != charPixelHeight) {
            possibleCharsBitmap = Bitmap.createBitmap(pixelsPerRow, (int) charPixelHeight, Bitmap.Config.ARGB_8888);
        }
        Canvas charsBitmapCanvas = new Canvas(possibleCharsBitmap);
        charsBitmapCanvas.drawARGB(255, 0, 0, 0);
        paint.setTextSize(textSize);
        paint.setColor(0xffffffff);
        for (int i = 0; i < result.pixelChars.length; i++) {
            charsBitmapCanvas.drawText(result.pixelChars[i], charPixelWidth * i, charPixelHeight, paint);
        }

        // Extract brightness bytes from the bitmap and flatten to a 1d array.
        int numCharsBitmapPixels = possibleCharsBitmap.getWidth() * possibleCharsBitmap.getHeight();
        if (possibleCharsBitmapPixels == null ||
                possibleCharsBitmapPixels.length != numCharsBitmapPixels) {
            possibleCharsBitmapPixels = new int[numCharsBitmapPixels];
            possibleCharsGrayscale = new byte[numCharsBitmapPixels];
        }

        possibleCharsBitmap.getPixels(possibleCharsBitmapPixels,
                0, possibleCharsBitmap.getWidth(), 0, 0,
                possibleCharsBitmap.getWidth(), possibleCharsBitmap.getHeight());
        for (int i = 0; i < possibleCharsBitmapPixels.length; i++) {
            // Each RGB component should be equal; take the blue.
            possibleCharsGrayscale[i] = (byte) (possibleCharsBitmapPixels[i] & 0xff);
        }

        // Create workers if needed, and assign them a subset of the rows to render.
        if (threadPool == null) {
            initRenderThreadPool(0);
        }
        int numWorkers = renderWorkers.size();
        for (int i = 0; i < numWorkers; i++) {
            renderWorkers.get(i).init(i, numWorkers,
                    result, (int) charPixelWidth, (int) charPixelHeight, possibleCharsGrayscale, bitmap);
        }

        try {
            threadPool.invokeAll(renderWorkers);
        } catch (InterruptedException ex) {
            android.util.Log.e("AsciiRenderer", "Interrupted", ex);
        }
        bitmap.prepareToDraw();

        if (DEBUG) {
            long t2 = System.nanoTime();
            long millis = (long) ((t2 - t1) / 1e6);
            int numThreads = (renderWorkers != null) ? renderWorkers.size() : 1;
            android.util.Log.e("AC", "Created output bitmap in " + millis + "ms using " + numThreads + " threads");
        }
    }

    private void fillPixelsInRow(int[] rowPixels, int numRowPixels,
                                 int[] asciiValues, int[] colorValues, int numValues,
                                 byte[] charsBitmap, int charWidth, int charHeight, int numChars) {
        int offset = 0;
        int pixelsPerRow = numValues * charWidth;
        // For each row of pixels:
        for (int y = 0; y < charHeight; y++) {
            // For each character to draw:
            for (int charPosition = 0; charPosition < numChars; charPosition++) {
                int charValue = asciiValues[charPosition];
                int charColor = colorValues[charPosition];
                // Index into the chars bitmap, going "down" the number of rows,
                // and "across" the amount of character widths given by the index.
                int charBitmapOffset = y * pixelsPerRow + charValue * charWidth;
                for (int i = 0; i < charWidth; i++) {
                    byte bitmapValue = charsBitmap[charBitmapOffset++];
                    rowPixels[offset++] = (bitmapValue != 0) ? charColor : 0xff000000;
                }
            }
        }
    }

    // Implemented in asciiart.c, almost identical to the above Java implementation.
    private native void fillPixelsInRowNative(int[] pixels, int numPixels,
                                              int[] asciiValues, int[] colorValues, int numValues,
                                              byte[] charsBitmap, int charWidth, int charHeight, int numChars);

    public Bitmap createBitmap(AsciiConverter.Result result) {
        int nextIndex = (activeBitmapIndex + 1) % bitmaps.length;
        if (bitmaps[nextIndex] == null ||
                bitmaps[nextIndex].getWidth() != outputImageWidth ||
                bitmaps[nextIndex].getHeight() != outputImageHeight) {
            bitmaps[nextIndex] = Bitmap.createBitmap(outputImageWidth, outputImageHeight, Bitmap.Config.ARGB_8888);
        }
        drawIntoBitmap(result, bitmaps[nextIndex]);
        activeBitmapIndex = nextIndex;
        return bitmaps[activeBitmapIndex];
    }

    // For thumbnails, create image one-fourth normal size, use every other row and column, and draw solid rectangles
    // instead of text because text won't scale down well for gallery view.
    public Bitmap createThumbnailBitmap(AsciiConverter.Result result) {
        int width = outputImageWidth / 4;
        int height = outputImageHeight / 4;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setARGB(255, 255, 255, 255);

        canvas.drawARGB(255, 0, 0, 0);
        if (result != null) {
            for (int r = 0; r < result.rows; r += 2) {
                int ymin = height * r / result.rows;
                int ymax = height * (r + 2) / result.rows;
                for (int c = 0; c < result.columns; c += 2) {
                    int xmin = width * c / result.columns;
                    int xmax = width * (c + 2) / result.columns;
                    float ratio = result.brightnessRatioAtRowColumn(r, c);
                    paint.setColor(result.colorAtRowColumn(r, c));
                    // for full color, always draw larger rectangle because colors will be darker
                    if (result.getColorType() == AsciiConverter.ColorType.FULL_COLOR || ratio > 0.5) {
                        canvas.drawRect(xmin, ymin, xmax, ymax, paint);
                    } else {
                        int x = (xmin + xmax) / 2 - 1;
                        int y = (ymin + ymax) / 2 - 1;
                        canvas.drawRect(x, y, x + 2, y + 2, paint);
                    }
                }
            }
        }
        return bitmap;
    }

    class Worker implements Callable<Long> {
        int startRow, endRow;
        int charPixelWidth, charPixelHeight;
        AsciiConverter.Result result;
        byte[] possibleCharsGrayscale;
        Bitmap outputBitmap;

        int[] rowAsciiValues;
        int[] rowColorValues;
        int[] renderedRowPixels;

        void init(int workerId, int numWorkers,
                  AsciiConverter.Result result,
                  int charPixelWidth, int charPixelHeight, byte[] possibleCharsGrayscale,
                  Bitmap outputBitmap) {
            this.startRow = result.rows * workerId / numWorkers;
            this.endRow = result.rows * (workerId + 1) / numWorkers;
            this.charPixelWidth = charPixelWidth;
            this.charPixelHeight = charPixelHeight;
            this.result = result;
            this.possibleCharsGrayscale = possibleCharsGrayscale;
            this.outputBitmap = outputBitmap;

            int pixelArraySize = charPixelWidth * charPixelHeight * result.columns;
            if (renderedRowPixels == null || renderedRowPixels.length != pixelArraySize) {
                renderedRowPixels = new int[pixelArraySize];
            }
            if (rowAsciiValues == null || rowAsciiValues.length != result.columns) {
                rowAsciiValues = new int[result.columns];
            }
            if (rowColorValues == null || rowColorValues.length != result.columns) {
                rowColorValues = new int[result.columns];
            }
        }

        // Returns time in nanoseconds to execute.
        @Override
        public Long call() throws Exception {
            long t1 = System.nanoTime();

            int pixelsPerRow = charPixelWidth * result.columns;
            for (int row = startRow; row < endRow; row++) {
                for (int col = 0; col < result.columns; col++) {
                    rowAsciiValues[col] = result.asciiIndexAtRowColumn(row, col);
                    rowColorValues[col] = result.colorAtRowColumn(row, col);
                }

                if (nativeCodeAvailable) {
                    fillPixelsInRowNative(renderedRowPixels, renderedRowPixels.length,
                            rowAsciiValues, rowColorValues, rowAsciiValues.length,
                            possibleCharsGrayscale, charPixelWidth, charPixelHeight, result.columns);
                } else {
                    fillPixelsInRow(renderedRowPixels, renderedRowPixels.length,
                            rowAsciiValues, rowColorValues, rowAsciiValues.length,
                            possibleCharsGrayscale, charPixelWidth, charPixelHeight, result.columns);
                }
                int y = charPixelHeight * row;
                // setPixels is not threadsafe; without synchronization some devices end up with
                // slightly garbled images.
                synchronized (outputBitmap) {
                    outputBitmap.setPixels(renderedRowPixels, 0, pixelsPerRow, 0, y, pixelsPerRow, charPixelHeight);
                }
            }
            return System.nanoTime() - t1;
        }
    }
}
