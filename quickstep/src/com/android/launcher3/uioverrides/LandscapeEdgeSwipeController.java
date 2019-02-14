package com.android.launcher3x.uioverrides;

import static com.android.launcher3x.LauncherState.NORMAL;
import static com.android.launcher3x.LauncherState.OVERVIEW;
import static com.android.quickstep.TouchInteractionService.EDGE_NAV_BAR;

import android.view.MotionEvent;

import com.android.launcher3x.AbstractFloatingView;
import com.android.launcher3x.Launcher;
import com.android.launcher3x.LauncherState;
import com.android.launcher3x.LauncherStateManager.AnimationComponents;
import com.android.launcher3x.touch.AbstractStateChangeTouchController;
import com.android.launcher3x.touch.SwipeDetector;
import com.android.launcher3x.userevent.nano.LauncherLogProto;
import com.android.launcher3x.userevent.nano.LauncherLogProto.Action.Direction;
import com.android.quickstep.RecentsModel;

/**
 * Touch controller for handling edge swipes in landscape/seascape UI
 */
public class LandscapeEdgeSwipeController extends AbstractStateChangeTouchController {

    private static final String TAG = "LandscapeEdgeSwipeCtrl";

    public LandscapeEdgeSwipeController(Launcher l) {
        super(l, SwipeDetector.HORIZONTAL);
    }

    @Override
    protected boolean canInterceptTouch(MotionEvent ev) {
        if (mCurrentAnimation != null) {
            // If we are already animating from a previous state, we can intercept.
            return true;
        }
        if (AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return false;
        }
        return mLauncher.isInState(NORMAL) && (ev.getEdgeFlags() & EDGE_NAV_BAR) != 0;
    }

    @Override
    protected LauncherState getTargetState(LauncherState fromState, boolean isDragTowardPositive) {
        boolean draggingFromNav = mLauncher.getDeviceProfile().isSeascape() != isDragTowardPositive;
        return draggingFromNav ? OVERVIEW : NORMAL;
    }

    @Override
    protected int getLogContainerTypeForNormalState() {
        return LauncherLogProto.ContainerType.NAVBAR;
    }

    @Override
    protected float getShiftRange() {
        return mLauncher.getDragLayer().getWidth();
    }

    @Override
    protected float initCurrentAnimation(@AnimationComponents int animComponent) {
        float range = getShiftRange();
        long maxAccuracy = (long) (2 * range);
        mCurrentAnimation = mLauncher.getStateManager().createAnimationToNewWorkspace(mToState,
                maxAccuracy, animComponent);
        return (mLauncher.getDeviceProfile().isSeascape() ? 2 : -2) / range;
    }

    @Override
    protected int getDirectionForLog() {
        return mLauncher.getDeviceProfile().isSeascape() ? Direction.RIGHT : Direction.LEFT;
    }

    @Override
    protected void onSwipeInteractionCompleted(LauncherState targetState, int logAction) {
        super.onSwipeInteractionCompleted(targetState, logAction);
        if (mStartState == NORMAL && targetState == OVERVIEW) {
            RecentsModel.getInstance(mLauncher).onOverviewShown(true, TAG);
        }
    }
}
