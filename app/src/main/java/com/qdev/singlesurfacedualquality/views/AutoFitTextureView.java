/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qdev.singlesurfacedualquality.views;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

/**
 * A {@link TextureView} that can be adjusted to a specified aspect ratio.
 */
public class AutoFitTextureView extends TextureView implements View.OnTouchListener {

    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    private Matrix mMatrix;

    private ScaleGestureDetector mScaleDetector;

    private MoveGestureDetector mMoveDetector;

    private float mScaleFactor = 1.f;

    private float mFocusX = 0.f;

    private float mFocusY = 0.f;

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {

        mMatrix = new Matrix();

        // Setup Gesture Detectors
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());

        mMoveDetector = new MoveGestureDetector(context, new MoveListener());

    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {

        if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {

            if (motionEvent.getPointerCount() == 1) {
                //MediaController show or hide
            }
        }

        mScaleDetector.onTouchEvent(motionEvent);

        mMoveDetector.onTouchEvent(motionEvent);

        float scaledImageCenterX = (getWidth() * mScaleFactor) / 2;

        float scaledImageCenterY = (getHeight() * mScaleFactor) / 2;

        mMatrix.reset();

        mMatrix.postScale(mScaleFactor, mScaleFactor);

        float dx = mFocusX - scaledImageCenterX;

        float dy = mFocusY - scaledImageCenterY;

        if (dx < ((1 - mScaleFactor) * getWidth())) {

            dx = (1 - mScaleFactor) * getWidth();

            mFocusX = dx + scaledImageCenterX;

        }

        if (dy < ((1 - mScaleFactor) * getHeight())) {

            dy = (1 - mScaleFactor) * getHeight();

            mFocusY = dy + scaledImageCenterY;

        }
        if (dx > 0) {

            dx = 0;

            mFocusX = dx + scaledImageCenterX;
        }

        if (dy > 0) {

            dy = 0;

            mFocusY = dy + scaledImageCenterY;
        }

        mMatrix.postTranslate(dx, dy);

        setTransform(mMatrix);

        setAlpha(1);

        return true; // indicate event was handled

    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            DisplayMetrics mMetrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getMetrics(mMetrics);
            double ratio = (double)mRatioWidth / (double)mRatioHeight;
            double invertedRatio = (double)mRatioHeight / (double)mRatioWidth;
            double portraitHeight = width * invertedRatio;
            double portraitWidth = width * (mMetrics.heightPixels / portraitHeight);
            double landscapeWidth = height * ratio;
            double landscapeHeight = (mMetrics.widthPixels / landscapeWidth) * height;

            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension((int)portraitWidth, mMetrics.heightPixels);
            } else {
                setMeasuredDimension(mMetrics.widthPixels, (int)landscapeHeight);
            }

            /*if (width > height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }*/
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {

            mScaleFactor *= detector.getScaleFactor(); // scale change since previous event

            // Don't let the object get too small or too large.
            mScaleFactor = Math.max(1.f, Math.min(mScaleFactor, 4.0f));

            return true;
        }
    }

    private class MoveListener extends MoveGestureDetector.SimpleOnMoveGestureListener {
        @Override
        public boolean onMove(MoveGestureDetector detector) {

            PointF d = detector.getFocusDelta();

            mFocusX += d.x;

            mFocusY += d.y;

            return true;
        }
    }

}
