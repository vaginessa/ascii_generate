package com.duy.acsiigenerator.emoticons;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.duy.acsiigenerator.clipboard.ClipboardManagerCompat;
import com.duy.acsiigenerator.clipboard.ClipboardManagerCompatFactory;

import java.util.ArrayList;

import imagetotext.duy.com.asciigenerator.R;

/**
 * Created by Duy on 06-May-17.
 */

public class FacesAdapter extends RecyclerView.Adapter<FacesAdapter.ViewHolder> {
    private static final String TAG = "ResultAdapter";
    private final ArrayList<String> objects = new ArrayList<>();
    private Context context;
    private LayoutInflater inflater;
    private ClipboardManagerCompat clipboardManagerCompat;
    private int color;

    public FacesAdapter(@NonNull Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.clipboardManagerCompat = ClipboardManagerCompatFactory.getManager(context);
    }


    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.list_item_emiticon, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.txtContent.setText(objects.get(position));
//        holder.share.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                Intent intent = new Intent(Intent.ACTION_SEND);
//                intent.putExtra(Intent.EXTRA_TEXT, holder.txtContent.getText().toString());
//                intent.setType("text/plain");
//                context.startActivity(intent);
//            }
//        });
//
//        holder.copy.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                clipboardManagerCompat.setText(holder.txtContent.getText().toString());
//                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
//            }
//        });
    }

    @Override
    public int getItemCount() {
        return objects.size();
    }

    public void clear() {
        this.objects.clear();
        notifyDataSetChanged();
    }

    public void add(String value) {
        this.objects.add(value);
        notifyItemInserted(objects.size() - 1);
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void addAll(ArrayList<String> list) {
        this.objects.clear();
        this.objects.addAll(list);
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        //        View copy, share;
        TextView txtContent;

        ViewHolder(View itemView) {
            super(itemView);
            txtContent = itemView.findViewById(R.id.text);
//            copy = itemView.findViewById(R.id.copy);
//            share = itemView.findViewById(R.id.share);
        }

    }
}

