package com.uong.phormi

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TabGroupsActivity:AppCompatActivity(){
 private lateinit var list:LinearLayout
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_tab_groups);list=findViewById(R.id.group_list);findViewById<TextView>(R.id.btn_groups_back).setOnClickListener{finish()};findViewById<TextView>(R.id.btn_group_new).setOnClickListener{createGroup()};render()}
 private fun render(){list.removeAllViews();val groups=TabGroupManager(this).list();if(groups.isEmpty()){list.addView(TextView(this).apply{text="No groups yet. Create a working unit for related tabs.";setTextColor(0xFF94A3B8.toInt());setPadding(18,30,18,30)});return};groups.forEach{g->val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,14,16,14);setBackgroundColor(0xFF172033.toInt())};row.addView(TextView(this).apply{text=g.name;textSize=17f;setTextColor(0xFFF8FAFC.toInt())});row.addView(TextView(this).apply{text="${g.urls.size} tabs${if(g.taskNote.isNotBlank())" · ${g.taskNote}"else""}";textSize=12f;setTextColor(0xFF94A3B8.toInt());setPadding(0,4,0,8)});row.setOnClickListener{editGroup(g.id)};list.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=8})}}
 private fun createGroup(){val input=EditText(this).apply{hint="Group name";setSingleLine(true)};AlertDialog.Builder(this).setTitle("New tab group").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create"){_,_->TabGroupManager(this).create(input.text.toString());render()}.show()}
 private fun editGroup(id:String){val g=TabGroupManager(this).list().firstOrNull{it.id==id}?:return;val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,0,16,0)};val name=EditText(this).apply{setText(g.name);setSingleLine(true)};val note=EditText(this).apply{setText(g.taskNote);hint="Task note"};box.addView(name);box.addView(note);AlertDialog.Builder(this).setTitle("Edit group").setView(box).setNegativeButton("Delete"){_,_->TabGroupManager(this).delete(id);render()}.setPositiveButton("Save"){_,_->TabGroupManager(this).rename(id,name.text.toString());TabGroupManager(this).setTaskNote(id,note.text.toString());render()}.show()}
}
