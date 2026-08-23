package jp.hyperequalizer.app.ui.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.SeparationStatus
import jp.hyperequalizer.app.databinding.SheetEqualizerBinding
import jp.hyperequalizer.app.databinding.ItemEqBandBinding
import jp.hyperequalizer.app.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * イコライザー(帯域調整+重低音強調)と、ボーカル/伴奏の個別音量調整を行う
 * ボトムシート。PlayerActivityから開かれ、分離再生の開始/音量変更は
 * PlayerActivityの公開APIを通じて行う。
 */
@UnstableApi
class EqualizerSheet : BottomSheetDialogFragment() {

    private var _binding: SheetEqualizerBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: MediaStateRepository
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private lateinit var currentUri: String
    private var audioSessionId: Int = 0
    private var separationStatus: SeparationStatus = SeparationStatus.NONE
    private var vocalPath: String? = null
    private var instrumentalPath: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as HyperEqApp
        repo = MediaStateRepository(app.database.mediaStateDao())
        currentUri = requireArguments().getString(ARG_URI)!!
        audioSessionId = requireArguments().getInt(ARG_SESSION_ID)

        setupAudioEffects()
        setupVocalSeparationUi()

        lifecycleScope.launch {
            val state = repo.getState(currentUri)
            separationStatus = try { SeparationStatus.valueOf(state.separationStatus) } catch (e: Exception) { SeparationStatus.NONE }
            vocalPath = state.separatedVocalPath
            instrumentalPath = state.separatedInstrumentalPath
            binding.vocalVolumeSeekBar.progress = (state.vocalVolume * 100).toInt().coerceIn(0, 200)
            binding.instrumentalVolumeSeekBar.progress = (state.instrumentalVolume * 100).toInt().coerceIn(0, 200)
            updateVolumeLabels()
            updateSeparationStatusUi()
            if (separationStatus == SeparationStatus.DONE && vocalPath != null && instrumentalPath != null) {
                playerActivity()?.activateSeparatedPlayback(
                    vocalPath!!, instrumentalPath!!,
                    binding.vocalVolumeSeekBar.progress / 100f,
                    binding.instrumentalVolumeSeekBar.progress / 100f
                )
            }
        }

        binding.vocalVolumeSeekBar.setOnSeekBarChangeListener(simpleSeekListener {
            updateVolumeLabels()
            applyVolumes()
        })
        binding.instrumentalVolumeSeekBar.setOnSeekBarChangeListener(simpleSeekListener {
            updateVolumeLabels()
            applyVolumes()
        })

        binding.btnSeparateNow.setOnClickListener { startSeparation() }
        binding.btnEqReset.setOnClickListener { resetEq() }
    }

    private fun playerActivity(): PlayerActivity? = activity as? PlayerActivity

    private fun applyVolumes() {
        val vocal = binding.vocalVolumeSeekBar.progress / 100f
        val instrumental = binding.instrumentalVolumeSeekBar.progress / 100f
        playerActivity()?.updateSeparatedVolumes(vocal, instrumental)
    }

    private fun updateVolumeLabels() {
        binding.vocalVolumeValueLabel.text = getString(R.string.percent_format, binding.vocalVolumeSeekBar.progress)
        binding.instrumentalVolumeValueLabel.text = getString(R.string.percent_format, binding.instrumentalVolumeSeekBar.progress)
    }

    private fun setupAudioEffects() {
        try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            val numberOfBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            binding.bandsContainer.removeAllViews()
            for (band in 0 until numberOfBands) {
                val bandShort = band.toShort()
                val itemBinding = ItemEqBandBinding.inflate(layoutInflater, binding.bandsContainer, false)
                val freqHz = eq.getCenterFreq(bandShort) / 1000
                itemBinding.bandFreqLabel.text = if (freqHz >= 1000) "${freqHz / 1000}kHz" else "${freqHz}Hz"
                itemBinding.bandSeekBar.max = (range[1] - range[0]).toInt()
                val initialLevel = eq.getBandLevel(bandShort)
                itemBinding.bandSeekBar.progress = (initialLevel - range[0]).toInt()
                itemBinding.bandValueLabel.text = formatDb(initialLevel)
                itemBinding.bandSeekBar.setOnSeekBarChangeListener(simpleSeekListener {
                    val level = (it + range[0]).toShort()
                    eq.setBandLevel(bandShort, level)
                    itemBinding.bandValueLabel.text = formatDb(level)
                    persistEqSoon()
                })
                binding.bandsContainer.addView(itemBinding.root)
            }
        } catch (e: Exception) {
            equalizer = null
        }

        try {
            val bb = BassBoost(0, audioSessionId)
            bassBoost = bb
            if (bb.strengthSupported) {
                binding.bassBoostSeekBar.setOnSeekBarChangeListener(simpleSeekListener {
                    bb.setStrength(it.toShort())
                    binding.bassBoostValueLabel.text = getString(R.string.percent_format, it * 100 / 1000)
                    persistEqSoon()
                })
            } else {
                binding.bassBoostSeekBar.isEnabled = false
            }
        } catch (e: Exception) {
            bassBoost = null
            binding.bassBoostSeekBar.isEnabled = false
        }

        lifecycleScope.launch {
            val state = repo.getState(currentUri)
            binding.eqEnableSwitch.isChecked = state.eqEnabled
            equalizer?.enabled = state.eqEnabled
            bassBoost?.enabled = state.eqEnabled
            binding.bassBoostSeekBar.progress = state.bassBoostStrength
            binding.bassBoostValueLabel.text = getString(R.string.percent_format, state.bassBoostStrength * 100 / 1000)
            bassBoost?.let { if (it.strengthSupported) it.setStrength(state.bassBoostStrength.toShort()) }
            state.eqBandLevelsCsv?.split(",")?.forEachIndexed { index, v ->
                val level = v.toShortOrNull() ?: return@forEachIndexed
                equalizer?.let { eq ->
                    if (index < eq.numberOfBands) {
                        eq.setBandLevel(index.toShort(), level)
                        val range = eq.bandLevelRange
                        val rowView = binding.bandsContainer.getChildAt(index)
                        rowView?.findViewById<SeekBar>(R.id.bandSeekBar)?.progress = (level - range[0]).toInt()
                        rowView?.findViewById<TextView>(R.id.bandValueLabel)?.text = formatDb(level)
                    }
                }
            }
        }

        binding.eqEnableSwitch.setOnCheckedChangeListener { _, checked ->
            equalizer?.enabled = checked
            bassBoost?.enabled = checked
            persistEqSoon()
        }
    }

    private fun formatDb(level: Short): String {
        val db = level / 100
        return if (db > 0) "+${db}dB" else "${db}dB"
    }

    /** 全帯域・重低音強調を0にリセットする(直感的に「元に戻す」操作ができるように) */
    private fun resetEq() {
        val eq = equalizer
        if (eq != null) {
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                eq.setBandLevel(band.toShort(), 0)
                val rowView = binding.bandsContainer.getChildAt(band)
                rowView?.findViewById<SeekBar>(R.id.bandSeekBar)?.progress = (0 - range[0]).toInt()
                rowView?.findViewById<TextView>(R.id.bandValueLabel)?.text = formatDb(0)
            }
        }
        bassBoost?.let {
            if (it.strengthSupported) {
                it.setStrength(0)
                binding.bassBoostSeekBar.progress = 0
                binding.bassBoostValueLabel.text = getString(R.string.percent_format, 0)
            }
        }
        persistEqSoon()
    }

    private fun setupVocalSeparationUi() {
        binding.vocalVolumeSeekBar.isEnabled = false
        binding.instrumentalVolumeSeekBar.isEnabled = false
        updateVolumeLabels()
    }

    private fun updateSeparationStatusUi() {
        val enabled = separationStatus == SeparationStatus.DONE
        binding.vocalVolumeSeekBar.isEnabled = enabled
        binding.instrumentalVolumeSeekBar.isEnabled = enabled
        binding.separationStatusText.text = when (separationStatus) {
            SeparationStatus.DONE -> getString(R.string.eq_separation_done)
            SeparationStatus.PROCESSING -> ""
            SeparationStatus.FAILED -> getString(R.string.eq_separation_failed)
            SeparationStatus.NONE -> getString(R.string.eq_separation_none)
        }
    }

    private fun startSeparation() {
        binding.btnSeparateNow.isEnabled = false
        binding.separationProgress.visibility = View.VISIBLE
        binding.separationProgress.progress = 0
        separationStatus = SeparationStatus.PROCESSING
        updateSeparationStatusUi()

        lifecycleScope.launch {
            repo.updateSeparation(currentUri, null, null, SeparationStatus.PROCESSING)
            val engine = SeparationEngine(requireContext())
            try {
                // 稀に処理がまったく進まなくなるケースがあっても、画面が永久に
                // 「処理中」表示のままになってしまわないよう、必ず一定時間で
                // 打ち切る安全策を入れている(AudioDecoder側にも同様の停滞検知が
                // あるが、二重の安全網としてここにも設けている)。
                val result = withTimeout(SEPARATION_TIMEOUT_MS) {
                    engine.separate(Uri.parse(currentUri)) { progress ->
                        if (_binding != null) {
                            binding.separationProgress.progress = progress
                        }
                    }
                }
                if (_binding == null) return@launch
                vocalPath = result.vocalPath
                instrumentalPath = result.instrumentalPath
                separationStatus = SeparationStatus.DONE
                repo.updateSeparation(currentUri, result.vocalPath, result.instrumentalPath, SeparationStatus.DONE)
                binding.btnSeparateNow.isEnabled = true
                binding.separationProgress.visibility = View.GONE
                binding.separationStatusText.text = if (result.usedAiModel) {
                    getString(R.string.eq_separation_done)
                } else {
                    getString(R.string.eq_separation_fallback)
                }
                playerActivity()?.activateSeparatedPlayback(
                    result.vocalPath, result.instrumentalPath,
                    binding.vocalVolumeSeekBar.progress / 100f,
                    binding.instrumentalVolumeSeekBar.progress / 100f
                )
            } catch (e: TimeoutCancellationException) {
                onSeparationFailed(getString(R.string.eq_separation_timeout))
            } catch (e: CancellationException) {
                // 画面が閉じられた等、通常のキャンセルはそのまま伝播させる(揉み消さない)
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError等、Exceptionではない失敗(Error系)もここで確実に拾い、
                // アプリ全体をクラッシュさせずに「失敗」として扱う。
                onSeparationFailed("${getString(R.string.eq_separation_failed)}(${e.message})")
            }
        }
    }

    private fun onSeparationFailed(message: String) {
        separationStatus = SeparationStatus.FAILED
        lifecycleScope.launch { repo.updateSeparation(currentUri, null, null, SeparationStatus.FAILED) }
        if (_binding == null) return
        binding.btnSeparateNow.isEnabled = true
        binding.separationProgress.visibility = View.GONE
        binding.separationStatusText.text = message
    }

    private var eqSaveJob: Job? = null
    private fun persistEqSoon() {
        eqSaveJob?.cancel()
        eqSaveJob = lifecycleScope.launch {
            delay(200)
            val eq = equalizer
            val csv = if (eq != null) {
                (0 until eq.numberOfBands).joinToString(",") { eq.getBandLevel(it.toShort()).toString() }
            } else null
            repo.updateEq(currentUri, csv, binding.eqEnableSwitch.isChecked, binding.bassBoostSeekBar.progress)
        }
    }

    private inline fun simpleSeekListener(crossinline onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }

    override fun onDestroyView() {
        super.onDestroyView()
        persistEqSoon()
        equalizer?.release()
        bassBoost?.release()
        _binding = null
    }

    companion object {
        private const val ARG_URI = "arg_uri"
        private const val ARG_SESSION_ID = "arg_session_id"

        /** 分離処理がこの時間を超えても終わらない場合は失敗として打ち切る(UIが永久に「処理中」のまま固まるのを防ぐ安全策) */
        private const val SEPARATION_TIMEOUT_MS = 10 * 60 * 1000L

        fun newInstance(uri: String, audioSessionId: Int): EqualizerSheet = EqualizerSheet().apply {
            arguments = bundleOf(ARG_URI to uri, ARG_SESSION_ID to audioSessionId)
        }
    }
}
