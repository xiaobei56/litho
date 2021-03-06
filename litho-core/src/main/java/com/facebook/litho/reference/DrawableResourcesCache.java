/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho.reference;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.util.LruCache;
import android.support.v4.util.Pools;
import android.util.StateSet;

import java.util.concurrent.atomic.AtomicInteger;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.HONEYCOMB;

/**
 * A cache that holds Drawables retreived from Android {@link android.content.res.Resources} for
 * each resId this class keeps a {@link android.support.v4.util.Pools.SynchronizedPool} of
 * DRAWABLES_POOL_MAX_ITEMS. The cache has a maximum capacity of DRAWABLES_MAX_ENTRIES. When the
 * cache is full it starts clearing memory deleting the less recently used pool of resources
 */
class DrawableResourcesCache {

  private static final int DRAWABLES_MAX_ENTRIES = 200;
  private static final int DRAWABLES_POOL_MAX_ITEMS = 10;

  private final LruCache<Integer, SimplePoolWithCount<Drawable>> mDrawableCache;

  DrawableResourcesCache() {
    mDrawableCache = new LruCache<Integer, SimplePoolWithCount<Drawable>>(DRAWABLES_MAX_ENTRIES) {
      @Override
      protected int sizeOf(Integer key, SimplePoolWithCount<Drawable> value) {
        return value.getPoolSize();
      }
    };
  }

  /**
   * @deprecated use {@link #get(int, Resources, Resources.Theme)}
   */
  @Nullable
  @Deprecated
  public Drawable get(int resId, Resources resources) {
    return get(resId, resources, null);
  }

  @Nullable
  public Drawable get(int resId, Resources resources, @Nullable Resources.Theme theme) {
    SimplePoolWithCount<Drawable> drawablesPool = mDrawableCache.get(resId);

    if (drawablesPool == null) {
      drawablesPool = new SimplePoolWithCount<>(DRAWABLES_POOL_MAX_ITEMS);
      mDrawableCache.put(resId, drawablesPool);
    }

    Drawable drawable = drawablesPool.acquire();

    if (drawable == null) {
      drawable = ResourcesCompat.getDrawable(resources, resId, theme);
    }

    // We never want this pool to remain empty otherwise we would risk to resolve a new drawable
    // when get is called again. So if the pool is about to drain we just put a new Drawable in it
    // to keep it warm.
    if (drawable != null && drawablesPool.getPoolSize() == 0) {
      drawablesPool.release(drawable.getConstantState().newDrawable());
    }

    return drawable;
  }

  public void release(Drawable drawable, int resId) {
    SimplePoolWithCount<Drawable> drawablesPool = mDrawableCache.get(resId);
    if (drawablesPool == null) {
      drawablesPool = new SimplePoolWithCount<>(DRAWABLES_POOL_MAX_ITEMS);
      mDrawableCache.put(resId, drawablesPool);
    }

    // Reset a stateful drawable, and its animations, before being released.
    if (drawable.isStateful()) {
      drawable.setState(StateSet.WILD_CARD);

      if (SDK_INT >= HONEYCOMB) {
        drawable.jumpToCurrentState();
      }
    }

    drawablesPool.release(drawable);
  }

  private static class SimplePoolWithCount<T> extends Pools.SynchronizedPool<T> {

    private final AtomicInteger mPoolSize;

    public SimplePoolWithCount(int maxPoolSize) {
      super(maxPoolSize);
      mPoolSize = new AtomicInteger(0);
    }

    @Override
    public T acquire() {
      T item = super.acquire();
      if (item != null) {
        mPoolSize.decrementAndGet();
      }

      return item;
    }

    @Override
    public boolean release(T instance) {
      boolean added = super.release(instance);
      if (added) {
        mPoolSize.incrementAndGet();
      }

      return added;
    }

    public int getPoolSize() {
      return mPoolSize.get();
    }
  }
}
