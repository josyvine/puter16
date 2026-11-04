package com.puter.unofficial.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.puter.unofficial.R;
import com.puter.unofficial.models.ScrapedImageItem;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewPager2 Adapter powering the full-screen swipeable image gallery viewer.
 * Manages full-resolution image streaming, progress indicators,
 * and page tap callbacks to toggle the attribution overlays.
 */
public class FullscreenGalleryAdapter extends RecyclerView.Adapter<FullscreenGalleryAdapter.GalleryPageViewHolder> {

    public interface OnGalleryPageClickListener {
        void onPageClick(int position, ScrapedImageItem item);
    }

    private final Context context;
    private final List<ScrapedImageItem> items = new ArrayList<>();
    private final OnGalleryPageClickListener pageClickListener;

    private static final ExecutorService galleryExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FullscreenGalleryAdapter(Context context, List<ScrapedImageItem> items, OnGalleryPageClickListener listener) {
        this.context = context;
        this.pageClickListener = listener;
        if (items != null) {
            this.items.addAll(items);
        }
    }

    public ScrapedImageItem getItem(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position);
        }
        return null;
    }

    @NonNull
    @Override
    public GalleryPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_fullscreen_gallery_page, parent, false);
        return new GalleryPageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GalleryPageViewHolder holder, int position) {
        ScrapedImageItem item = items.get(position);
        holder.bind(item, position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class GalleryPageViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivFullscreen;
        private final ProgressBar pbLoading;

        GalleryPageViewHolder(@NonNull View itemView) {
            super(itemView);
            ivFullscreen = itemView.findViewById(R.id.ivFullscreenImage);
            pbLoading = itemView.findViewById(R.id.pbGalleryLoading);
        }

        void bind(final ScrapedImageItem item, final int position) {
            if (item == null) return;

            final String imageUrl = item.getImageUrl();
            ivFullscreen.setImageDrawable(null);
            ivFullscreen.setTag(imageUrl);
            pbLoading.setVisibility(View.VISIBLE);

            itemView.setOnClickListener(v -> {
                if (pageClickListener != null) {
                    pageClickListener.onPageClick(position, item);
                }
            });

            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                pbLoading.setVisibility(View.GONE);
                ivFullscreen.setImageResource(R.drawable.ic_no_data);
                return;
            }

            galleryExecutor.execute(() -> {
                Bitmap bitmap = downloadHighResBitmap(imageUrl);
                mainHandler.post(() -> {
                    if (imageUrl.equals(ivFullscreen.getTag())) {
                        pbLoading.setVisibility(View.GONE);
                        if (bitmap != null) {
                            ivFullscreen.setImageBitmap(bitmap);
                        } else {
                            ivFullscreen.setImageResource(R.drawable.ic_no_data);
                        }
                    }
                });
            });
        }
    }

    /**
     * Downloads full-resolution bitmap stream for the full-screen view.
     */
    private static Bitmap downloadHighResBitmap(String urlString) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            inputStream = connection.getInputStream();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(inputStream, null, options);
        } catch (Exception e) {
            return null;
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}