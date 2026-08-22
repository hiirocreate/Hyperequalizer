package jp.hyperequalizer.app.ui.equalizer

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
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
import kotlinx.coroutines.launch

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
            binding.vocalVolumeValueLabel.text = getString(R.string.percent_format, binding.vocalVolumeSeekBar.progress)
            binding.instrumentalVolumeValueLabel.text =
                getString(R.string.percent_format, binding.instrumentalVolumeSeekBar.progress)
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
            binding.vocalVolumeValueLabel.text = getString(R.string.percent_format, it)
            applyVolumes()
        })
        binding.instrumentalVolumeSeekBar.setOnSeekBarChangeListener(simpleSeekListener {
            binding.instrumentalVolumeValueLabel.text = getString(R.string.percent_format, it)
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
                itemBinding.bandSeekBar.progress = (eq.getBandLevel(bandShort) - range[0]).toInt()
                itemBinding.bandValueLabel.text = formatDb(eq.getBandLevel(bandShort))
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
                    binding.bassBoostValueLabel.text = getString(R.string.percent_format, it / 10)
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
            binding.bassBoostValueLabel.text = getString(R.string.percent_format, state.bassBoostStrength / 10)
            bassBoost?.let { if (it.strengthSupported) it.setStrength(state.bassBoostStrength.toShort()) }
            state.eqBandLevelsCsv?.split(",")?.forEachIndexed { index, v ->
                val level = v.toShortOrNull() ?: return@forEachIndexed
                equalizer?.let { eq ->
                    if (index < eq.numberOfBands) {
                        eq.setBandLevel(index.toShort(), level)
                        val range = eq.bandLevelRange
                        val rowView = binding.bandsContainer.getChildAt(index)
                        rowView?.findViewById<SeekBar>(R.id.bandSeekBar)?.progress = (level - range[0]).toInt()
                        rowView?.findViewById<android.widget.TextView>(R.id.bandValueLabel)?.text = formatDb(level)
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

    /** ミリベル(1/100dB)単位の帯域ゲインを、+3dB/0dB/-2dBのような分かりやすい表記にする */
    private fun formatDb(levelMillibel: Short): String {
        val db = levelMillibel / 100
        return when {
            db > 0 -> "+${db}dB"
            db < 0 -> "${db}dB"
            else -> "0dB"
        }
    }

    /** 全帯域と重低音強調を基準値(変化なし)に戻す */
    private fun resetEq() {
        val eq = equalizer
        if (eq != null) {
            for (band in 0 until eq.numberOfBands) {
                eq.setBandLevel(band.toShort(), 0)
                val rowView = binding.bandsContainer.getChildAt(band)
                val range = eq.bandLevelRange
                rowView?.findViewById<SeekBar>(R.id.bandSeekBar)?.progress = (0 - range[0]).toInt()
                rowView?.findViewById<android.widget.TextView>(R.id.bandValueLabel)?.text = formatDb(0)
            }
        }
        bassBoost?.let { if (it.strengthSupported) it.setStrength(0) }
        binding.bassBoostSeekBar.progress = 0
        binding.bassBoostValueLabel.text = getString(R.string.percent_format, 0)
        persistEqSoon()
    }

    private fun setupVocalSeparationUi() {
        binding.vocalVolumeSeekBar.isEnabled = false
        binding.instrumentalVolumeSeekBar.isEnabled = false
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
            // decode/書き出し処理のどこかで例外(ストレージ不足やI/Oエラーなど)が
            // 発生した場合、以前は捕捉されずクラッシュしていた。ここで確実に受け止め、
            // 「失敗」として画面に理由を表示するだけに留める。
            var failureReason: String? = null
            val result = try {
                engine.separate(Uri.parse(currentUri)) { progress ->
                    binding.separationProgress.progress = progress
                }
            } catch (e: Exception) {
                failureReason = e.message
                null
            }
            binding.btnSeparateNow.isEnabled = true
            binding.separationProgress.visibility = View.GONE
            if (result != null) {
                vocalPath = result.vocalPath
                instrumentalPath = result.instrumentalPath
                separationStatus = SeparationStatus.DONE
                repo.updateSeparation(currentUri, result.vocalPath, result.instrumentalPath, SeparationStatus.DONE)
                binding.separationStatusText.text = if (result.usedAiModel) {
                    getString(R.string.eq_separation_done)
                } else {
                    getString(R.string.eq_separation_fallback)
                }
                updateSeparationStatusUi()
                playerActivity()?.activateSeparatedPlayback(
                    result.vocalPath, result.instrumentalPath,
                    binding.vocalVolumeSeekBar.progress / 100f,
                    binding.instrumentalVolumeSeekBar.progress / 100f
                )
            } else {
                separationStatus = SeparationStatus.FAILED
                repo.updateSeparation(currentUri, null, null, SeparationStatus.FAILED)
                updateSeparationStatusUi()
                // 失敗理由が分かる場合は併記する(音声トラックが無い/デコード不可、など)
                if (failureReason != null) {
                    binding.separationStatusText.text =
                        "${getString(R.string.eq_separation_failed)}(${failureReason})"
                }
            }
        }
    }

    private var eqSaveJob: kotlinx.coroutines.Job? = null
    private fun persistEqSoon() {
        eqSaveJob?.cancel()
        eqSaveJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
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

        fun newInstance(uri: String, audioSessionId: Int): EqualizerSheet = EqualizerSheet().apply {
            arguments = bundleOf(ARG_URI to uri, ARG_SESSION_ID to audioSessionId)
        }
    }
}
