package app.tiredfone.sclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import app.tiredfone.sclient.databinding.ActivityChangelogBinding

class ChangelogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangelogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangelogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.adapter = ChangelogAdapter(Changelog.entries)
    }

    private class ChangelogAdapter(private val entries: List<ChangelogEntry>) :
        RecyclerView.Adapter<ChangelogAdapter.ViewHolder>() {

        inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_changelog, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.view.findViewById<TextView>(R.id.tvVersion).text = "v${entry.version}"
            holder.view.findViewById<TextView>(R.id.tvDate).text = entry.date
            val changesContainer = holder.view.findViewById<ViewGroup>(R.id.changesContainer)
            changesContainer.removeAllViews()
            entry.changes.forEach { change ->
                val tv = TextView(holder.view.context).apply {
                    text = "• $change"
                    textSize = 13f
                    setTextColor(context.getColor(R.color.text_secondary))
                    setPadding(0, 2, 0, 2)
                }
                changesContainer.addView(tv)
            }
        }

        override fun getItemCount() = entries.size
    }
}
