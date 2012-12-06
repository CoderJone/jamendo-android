/*
 * Copyright (C) 2012 Marcin Gil <marcin.gil@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.teleca.jamendo.adapter;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.teleca.jamendo.R;
import com.teleca.jamendo.activity.RadioActivity.RadioChannel;

public class RadioChannelAdapter extends ArrayListAdapter<RadioChannel> {
    int mIconSize;

    public RadioChannelAdapter(Activity context) {
        super(context);
        mIconSize = (int) context.getResources().getDimension(R.dimen.icon_size);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;

        ViewHolder holder;

        if (row == null) {
            LayoutInflater inflater = mContext.getLayoutInflater();
            row = inflater.inflate(R.layout.purple_row, null);

            holder = new ViewHolder();
            holder.image = (ImageView) row.findViewById(R.id.PurpleImageView);
            LayoutParams lp = holder.image.getLayoutParams();
            lp.height = mIconSize;
            lp.width = mIconSize;
            holder.image.setLayoutParams(lp);
            holder.text = (TextView) row.findViewById(R.id.PurpleRowTextView);

            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }

        holder.text.setText(mList.get(position).getTitle());
        holder.image.setImageResource(mList.get(position).getIconId());

        return row;
    }

    /**
     * Class implementing holder pattern, performance boost
     * 
     * @author Marcin Gil
     */
    static class ViewHolder {
        ImageView image;
        TextView text;
    }

}
