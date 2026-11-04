package com.puter.unofficial.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
 * Horizontal Carousel Adapter rendering web image cards inside the assistant chat bubble.
 * Features asynchronous image fetching, memory LruCache for smooth horizontal swiping,
 * and click delegation to launch the fullscreen gallery viewer.
 */
public class ScrapedImageCarouselAdapter extends RecyclerView.Adapter<ScrapedImageCarouselAdapter.CarouselViewHolder> {

    public interface OnImageClickListener {
        void onImageClick(int position, ScrapedImageItem selectedItem, List<ScrapedImageItem> allItems);
    }

    private final Context context;
    private final List<ScrapedImageItem> imageItems = new ArrayList<>();
    private final OnImageClickListener clickListener;

    private static final int MAX_CACHE_SIZE = (int) (Runtime.getRuntime().maxMemory() / 1024) / 8;
    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private static final ExecutorService imageLoaderExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ScrapedImageCarouselAdapter(Context context, List<ScrapedImageItem> items, OnImageClickListener listener) {
        this.context = context;
        this.clickListener = listener;
        if (items != null) {
            this.imageItems.addAll(items);
        }
    }

    public void updateData(List<ScrapedImageItem> newItems) {
        this.imageItems.clear();
        if (newItems != null) {
            this.imageItems.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CarouselViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_scraped_image_card, parent, false);
        return new CarouselViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CarouselViewHolder holder, int position) {
        ScrapedImageItem item = imageItems.get(position);
        holder.bind(item, position);
    }

    @Override
    public int getItemCount() {
        return imageItems.size();
    }

    class CarouselViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivThumbnail;
        private final TextView tvDomainBadge;
        private final TextView tvTitle;

        CarouselViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.ivCardThumbnail);
            tvDomainBadge = itemView.findViewById(R.id.tvCardDomain);
            tvTitle = itemView.findViewById(R.id.tvCardTitle);
        }

        void bind(final ScrapedImageItem item, final int position) {
            if (item == null) return;

            // 1. Text & Domain Badge setup
            String domain = item.getSourceDomain();
            if (domain == null || domain.trim().isEmpty()) {
                domain = "Web Source";
            }
            tvDomainBadge.setText(domain);

            String title = item.getTitle();
            if (title != null && !title.trim().isEmpty()) {
                tvTitle.setText(title.trim());
                tvTitle.setVisibility(View.VISIBLE);
            } else {
                tvTitle.setVisibility(View.GONE);
            }

            // 2. Click delegation
            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onImageClick(position, item, new ArrayList<>(imageItems));
                }
            });

            // 3. Remote Image Loading with LruCache & View Tagging
            final String imageUrl = item.getImageUrl();
            ivThumbnail.setImageDrawable(null);
            ivThumbnail.setTag(imageUrl);

            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                ivThumbnail.setImageResource(R.drawable.ic_no_data);
                return;
            }

            Bitmap cachedBitmap = memoryCache.get(imageUrl);
            if (cachedBitmap != null) {
                ivThumbnail.setImageBitmap(cachedBitmap);
                return;
            }

            // Fallback placeholder while fetching
            ivThumbnail.setImageResource(R.drawable.ic_launcher_background);

            imageLoaderExecutor.execute(() -> {
                Bitmap downloaded = downloadBitmap(imageUrl);
                if (downloaded != null) {
                    memoryCache.put(imageUrl, downloaded);
                    mainHandler.post(() -> {
                        if (imageUrl.equals(ivThumbnail.getTag())) {
                            ivThumbnail.setImageBitmap(downloaded);
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (imageUrl.equals(ivThumbnail.getTag())) {
                            ivThumbnail.setImageResource(R.drawable.ic_no_data);
                        }
                    });
                }
            });
        }
    }

    /**
     * Downloads and safely downsamples image bitmap over HTTP/HTTPS to prevent OutOfMemory errors.
     */
    private static Bitmap downloadBitmap(String urlStr) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            conn.connect();

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            is = conn.getInputStream();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Optimized memory consumption
            return BitmapFactory.decodeStream(is, null, options);
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}