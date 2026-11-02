package com.kuronami.mdmvista.client.media;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.av_compare_ts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.Pointer;

/** Packet-copy remux for two addon-owned local inputs; it never opens a network URL or decodes/re-encodes. */
final class SplitStreamPacketMuxer {
    private static final int MAX_INPUT_STREAMS = 32;
    private static final int SIZE_CHECK_PACKET_INTERVAL = 64;

    record StreamStats(long packets, long firstPts, long lastPts, long firstDts, long lastDts) {}
    record MuxStats(StreamStats video, StreamStats audio, long outputBytes) {}

    private SplitStreamPacketMuxer() {}

    static boolean hasVideoAndAudio(Path file, BooleanSupplier current, long deadlineNanos) throws IOException {
        Objects.requireNonNull(file, "file");
        requireActive(current, deadlineNanos);
        if (!Files.isRegularFile(file) || Files.size(file) <= 0) return false;
        AVFormatContext input = new AVFormatContext((Pointer) null);
        try {
            if (avformat_open_input(input, file.toString(), null, (AVDictionary) null) < 0 || input.isNull()) return false;
            requireActive(current, deadlineNanos);
            if (avformat_find_stream_info(input, (AVDictionary) null) < 0) return false;
            boolean video = false;
            boolean audio = false;
            int count = Math.min(input.nb_streams(), MAX_INPUT_STREAMS);
            for (int i = 0; i < count; i++) {
                requireActive(current, deadlineNanos);
                int type = input.streams(i).codecpar().codec_type();
                video |= type == AVMEDIA_TYPE_VIDEO;
                audio |= type == AVMEDIA_TYPE_AUDIO;
            }
            return input.nb_streams() <= MAX_INPUT_STREAMS && video && audio;
        } finally {
            closeInput(input);
        }
    }

    static MuxStats mux(Path videoFile, Path audioFile, Path outputFile,
            BooleanSupplier current, long deadlineNanos, long maxOutputBytes) throws IOException {
        Objects.requireNonNull(videoFile, "videoFile");
        Objects.requireNonNull(audioFile, "audioFile");
        Objects.requireNonNull(outputFile, "outputFile");
        requireActive(current, deadlineNanos);
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes must be positive");
        if (!Files.isRegularFile(videoFile) || !Files.isRegularFile(audioFile))
            throw new IOException("split media pair is incomplete");
        if (videoFile.toRealPath().equals(audioFile.toRealPath()))
            throw new IOException("split media inputs must be distinct files");

        AVFormatContext videoInput = new AVFormatContext((Pointer) null);
        AVFormatContext audioInput = new AVFormatContext((Pointer) null);
        AVFormatContext output = new AVFormatContext((Pointer) null);
        AVPacket videoPacket = null;
        AVPacket audioPacket = null;
        AVIOContext outputIo = new AVIOContext((Pointer) null);
        boolean outputIoOpen = false;
        try {
            openInput(videoInput, videoFile, current, deadlineNanos);
            openInput(audioInput, audioFile, current, deadlineNanos);
            AVStream sourceVideo = oneStream(videoInput, AVMEDIA_TYPE_VIDEO, current, deadlineNanos);
            AVStream sourceAudio = oneStream(audioInput, AVMEDIA_TYPE_AUDIO, current, deadlineNanos);
            if (hasStreamType(videoInput, AVMEDIA_TYPE_AUDIO) || hasStreamType(audioInput, AVMEDIA_TYPE_VIDEO))
                throw new IOException("expected separate video-only and audio-only inputs");

            requireActive(current, deadlineNanos);
            int allocated = avformat_alloc_output_context2(output, null, "mp4", outputFile.toString());
            if (allocated < 0 || output.isNull()) throw ffmpegFailure("allocate MP4 output", allocated);
            AVStream outputVideo = copyStream(output, sourceVideo);
            AVStream outputAudio = copyStream(output, sourceAudio);
            output.max_interleave_delta(1_000_000L);

            if ((output.oformat().flags() & AVFMT_NOFILE) == 0) {
                if (avio_open(outputIo, outputFile.toString(), AVIO_FLAG_WRITE) < 0)
                    throw new IOException("could not open bounded MP4 staging output");
                output.pb(outputIo);
                outputIoOpen = true;
            }
            requireActive(current, deadlineNanos);
            int header = avformat_write_header(output, (AVDictionary) null);
            if (header < 0) throw ffmpegFailure("write MP4 header", header);
            videoPacket = av_packet_alloc();
            audioPacket = av_packet_alloc();
            if (videoPacket == null || videoPacket.isNull() || audioPacket == null || audioPacket.isNull())
                throw new IOException("could not allocate bounded packet-copy state");
            PacketState v = new PacketState(videoInput, sourceVideo, output, outputVideo, videoPacket);
            PacketState a = new PacketState(audioInput, sourceAudio, output, outputAudio, audioPacket);
            long packetCount = 0;
            while (!v.eof || !a.eof || v.ready || a.ready) {
                requireActive(current, deadlineNanos);
                if (!v.ready && !v.eof) v.ready = readSelected(v, current, deadlineNanos);
                if (!a.ready && !a.eof) a.ready = readSelected(a, current, deadlineNanos);
                if (!v.ready && !a.ready) continue;
                PacketState next = selectNext(v, a);
                requireActive(current, deadlineNanos);
                next.write(current, deadlineNanos);
                packetCount++;
                if ((packetCount % SIZE_CHECK_PACKET_INTERVAL) == 0) checkOutputSize(outputFile, maxOutputBytes);
            }
            if (v.stats.packets == 0 || a.stats.packets == 0)
                throw new IOException("split media pair did not contain both packet streams");
            requireActive(current, deadlineNanos);
            if (av_write_trailer(output) < 0) throw new IOException("could not finalize MP4 staging output");
            checkOutputSize(outputFile, maxOutputBytes);
            long outputBytes = Files.size(outputFile);
            return new MuxStats(v.stats.snapshot(), a.stats.snapshot(), outputBytes);
        } finally {
            if (videoPacket != null && !videoPacket.isNull()) av_packet_free(videoPacket);
            if (audioPacket != null && !audioPacket.isNull()) av_packet_free(audioPacket);
            if (outputIoOpen && !outputIo.isNull()) {
                avio_closep(outputIo);
                if (!output.isNull()) output.pb((AVIOContext) null);
            }
            if (!output.isNull()) avformat_free_context(output);
            closeInput(audioInput);
            closeInput(videoInput);
        }
    }

    private static void openInput(AVFormatContext input, Path path,
            BooleanSupplier current, long deadlineNanos) throws IOException {
        requireActive(current, deadlineNanos);
        if (avformat_open_input(input, path.toString(), null, (AVDictionary) null) < 0 || input.isNull())
            throw new IOException("could not open local split media input");
        requireActive(current, deadlineNanos);
        if (input.nb_streams() <= 0 || input.nb_streams() > MAX_INPUT_STREAMS
                || avformat_find_stream_info(input, (AVDictionary) null) < 0)
            throw new IOException("local split media has invalid stream metadata");
        requireActive(current, deadlineNanos);
    }

    private static AVStream oneStream(AVFormatContext input, int type,
            BooleanSupplier current, long deadlineNanos) throws IOException {
        AVStream selected = null;
        for (int i = 0; i < input.nb_streams(); i++) {
            requireActive(current, deadlineNanos);
            AVStream stream = input.streams(i);
            if (stream.codecpar().codec_type() != type) continue;
            if (selected != null) throw new IOException("multiple selected media streams are unsupported");
            selected = stream;
        }
        if (selected == null || selected.time_base().num() <= 0 || selected.time_base().den() <= 0)
            throw new IOException("required split media stream is missing or has invalid time base");
        return selected;
    }

    private static boolean hasStreamType(AVFormatContext input, int type) {
        for (int i = 0; i < input.nb_streams(); i++)
            if (input.streams(i).codecpar().codec_type() == type) return true;
        return false;
    }

    private static AVStream copyStream(AVFormatContext output, AVStream source) throws IOException {
        AVStream target = avformat_new_stream(output, null);
        if (target == null || target.isNull()) throw new IOException("could not allocate MP4 output stream");
        AVCodecParameters sourceParameters = source.codecpar();
        AVCodecParameters targetParameters = target.codecpar();
        int copy = avcodec_parameters_copy(targetParameters, sourceParameters);
        if (copy < 0) throw ffmpegFailure("copy codec parameters", copy);
        targetParameters.codec_tag(0);
        target.time_base(source.time_base());
        return target;
    }

    private static boolean readSelected(PacketState state, BooleanSupplier current, long deadlineNanos)
            throws IOException {
        while (true) {
            requireActive(current, deadlineNanos);
            int result = av_read_frame(state.input, state.packet);
            if (result == AVERROR_EOF) {
                state.eof = true;
                return false;
            }
            if (result < 0) throw ffmpegFailure("read local media packet", result);
            if (state.packet.stream_index() == state.inputStream.index()) {
                state.stats.observe(state.packet);
                return true;
            }
            av_packet_unref(state.packet);
        }
    }

    private static PacketState selectNext(PacketState video, PacketState audio) {
        if (!video.ready) return audio;
        if (!audio.ready) return video;
        long videoDts = timestamp(video.packet);
        long audioDts = timestamp(audio.packet);
        if (videoDts == AV_NOPTS_VALUE && audioDts == AV_NOPTS_VALUE) return video;
        if (videoDts == AV_NOPTS_VALUE) return audio;
        if (audioDts == AV_NOPTS_VALUE) return video;
        int order = av_compare_ts(videoDts, video.inputStream.time_base(), audioDts, audio.inputStream.time_base());
        return order <= 0 ? video : audio;
    }

    private static long timestamp(AVPacket packet) {
        return packet.dts() != AV_NOPTS_VALUE ? packet.dts() : packet.pts();
    }

    private static void checkOutputSize(Path path, long maxOutputBytes) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > maxOutputBytes)
            throw new IOException("packet-copy output exceeded its fixed size bound");
    }

    private static void requireActive(BooleanSupplier current, long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean())
            throw new IOException("split AV acquisition was superseded");
        if (System.nanoTime() >= deadlineNanos)
            throw new IOException("split AV packet-copy exceeded its fixed deadline");
    }

    private static IOException ffmpegFailure(String operation, int code) {
        return new IOException(operation + " failed with FFmpeg status " + code);
    }

    private static void closeInput(AVFormatContext input) {
        if (input != null && !input.isNull()) avformat_close_input(input);
    }

    private static final class PacketState {
        private final AVFormatContext input;
        private final AVStream inputStream;
        private final AVFormatContext output;
        private final AVStream outputStream;
        private final AVPacket packet;
        private final MutableStats stats = new MutableStats();
        private boolean ready;
        private boolean eof;

        private PacketState(AVFormatContext input, AVStream inputStream, AVFormatContext output,
                AVStream outputStream, AVPacket packet) {
            this.input = input;
            this.inputStream = inputStream;
            this.output = output;
            this.outputStream = outputStream;
            this.packet = packet;
        }

        private void write(BooleanSupplier current, long deadlineNanos) throws IOException {
            requireActive(current, deadlineNanos);
            av_packet_rescale_ts(packet, inputStream.time_base(), outputStream.time_base());
            packet.stream_index(outputStream.index()).pos(-1);
            int result = av_interleaved_write_frame(output, packet);
            av_packet_unref(packet);
            ready = false;
            if (result < 0) throw ffmpegFailure("write packet-copy MP4 packet", result);
        }

    }

    private static final class MutableStats {
        private long packets;
        private long firstPts = AV_NOPTS_VALUE;
        private long lastPts = AV_NOPTS_VALUE;
        private long firstDts = AV_NOPTS_VALUE;
        private long lastDts = AV_NOPTS_VALUE;

        private void observe(AVPacket packet) {
            if (packets == 0) {
                firstPts = packet.pts();
                firstDts = packet.dts();
            }
            packets++;
            lastPts = packet.pts();
            lastDts = packet.dts();
        }

        private StreamStats snapshot() { return new StreamStats(packets, firstPts, lastPts, firstDts, lastDts); }
    }
}
