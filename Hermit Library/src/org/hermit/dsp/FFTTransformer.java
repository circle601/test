
/**
 * dsp: various digital signal processing algorithms
 * <br>Copyright 2009 Ian Cameron Smith
 *
 * <p>This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation (see COPYING).
 * 
 * <p>This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */


package org.hermit.dsp;

import org.hermit.utils.Bitwise;

import ca.uol.aig.fftpack.RealDoubleFFT;


/**
 * Implementation of the Cooley–Tukey FFT algorithm by Tsan-Kuang Lee,
 * for real-valued data and results:
 * http://www.ling.upenn.edu/~tklee/Projects/dsp/
 * 
 * <p>His copyright statement: "Do whatever you want with the code.
 * Feedbacks and improvement welcome."
 * 
 * <p>Usage: create an FFTTransformer with a specified block size, to
 * pre-allocate the necessary resources.  Then, for each block that
 * you want to transform:
 * <ul>
 * <li>Call {@link #setInput(float[], int, int)} to
 *     supply the input data.  The execution of this method is the only
 *     time your input buffer will be accessed; the data is converted
 *     to complex and copied to a different buffer.
 * <li>Call {@link #transform()} to actually do the FFT.  This is the
 *     time-consuming part.
 * <li>Call {@link #getResults(float[])} to get the results into
 *     your output buffer.
 * </ul>
 * <p>The flow is broken up like this to allow you to make best use of
 * locks.  For example, if the input buffer is also accessed by a thread
 * which reads from the audio, you only need to lock out that thread during
 * {@link #setInput(float[], int, int)}, not the entire FFT process.
 */
public final class FFTTransformer {

    // ******************************************************************** //
    // Constructor.
    // ******************************************************************** //

    /**
     * Create an FFT transformer for a given sample size.  This preallocates
     * resources appropriate to that block size.
     * 
     * @param   size        The number of samples in a block that we will
     *                      be asked to transform.  Must be a power of 2.
     * @throws  IllegalArgumentException    Invalid parameter.
     */
    public FFTTransformer(int size) {
        if (!Bitwise.isPowerOf2(size))
            throw new IllegalArgumentException("size for FFT must" +
                                               " be a power of 2 (was " + size + ")");
        
        transformer = new RealDoubleFFT(size);
        
        blockSize = size;
        
        // Allocate working data arrays.
        xre = new double[blockSize];
    }
    

    // ******************************************************************** //
    // Data Setup.
    // ******************************************************************** //

    /**
     * Set up a new data block for the FFT algorithm.  The data in
     * the provided buffer will be copied out, and that buffer
     * will not be referenced again.  This is separated
     * out from the main computation to allow for more efficient use
     * of locks.
     * 
     * @param   input       The input data buffer.
     * @param   off         Offset in the buffer at which the data to
     *                      be transformed starts.
     * @param   count       Number of samples in the data to be
     *                      transformed.  Must be the same as the size
     *                      parameter that was given to the constructor.
     * @throws  IllegalArgumentException    Invalid data size.
     */
    public final void setInput(float[] input, int off, int count) {
        if (count != blockSize)
            throw new IllegalArgumentException("bad input count in FFT:" +
                                               " constructed for " + blockSize +
                                               "; given " + input.length);
       
        for (int i = 0; i < blockSize; i++)
            xre[i] = input[off + i];
    }
    

    /**
     * Set up a new data block for the FFT algorithm.  The data in
     * the provided buffer will be copied out, and that buffer
     * will not be referenced again.  This is separated
     * out from the main computation to allow for more efficient use
     * of locks.
     * 
     * @param   input       The input data buffer.
     * @param   off         Offset in the buffer at which the data to
     *                      be transformed starts.
     * @param   count       Number of samples in the data to be
     *                      transformed.  Must be the same as the size
     *                      parameter that was given to the constructor.
     * @throws  IllegalArgumentException    Invalid data size.
     */
    public final void setInput(short[] input, int off, int count) {
        if (count != blockSize)
            throw new IllegalArgumentException("bad input count in FFT:" +
                                               " constructed for " + blockSize +
                                               "; given " + input.length);
       
        for (int i = 0; i < blockSize; i++)
            xre[i] = (float) input[off + i] / 32768f;
    }
    

    // ******************************************************************** //
    // Transform.
    // ******************************************************************** //

    /**
     * Transform the data provided in the last call to setInput.
     */
    public final void transform() {
        transformer.ft(xre);
    }


    // ******************************************************************** //
    // Results.
    // ******************************************************************** //

    /**
     * Get the real results of the last transformation.
     * 
     * @param   buffer  Buffer in which the real part of the results
     *                  will be placed.  This buffer must be half the
     *                  length of the input block.  If transform() has
     *                  not been called, the results will be garbage.
     * @return          The parameter buffer.
     * @throws  IllegalArgumentException    Invalid buffer size.
     */
    public final float[] getResults(float[] buffer) {
        if (buffer.length != blockSize / 2)
            throw new IllegalArgumentException("bad output buffer size in FFT:" +
                                               " must be " + (blockSize / 2) +
                                               "; given " + buffer.length);
       
        for (int i = 0; i < blockSize / 2; i++) {
            float r = (float) xre[i * 2];
            float im = i == 0 ? 0f : (float) xre[i * 2 - 1];
            buffer[i] = (float) (Math.sqrt(r * r + im * im)) / blockSize;
        }
        return buffer;
    }


    /**
     * Get the rolling average real results of the last n transformations.
     * 
     * @param   average     Buffer in which the averaged real part of the
     *                      results will be maintained.  This buffer must be
     *                      half the length of the input block.  It is
     *                      important that this buffer is kept intact and
     *                      undisturbed between calls, as the average
     *                      calculation for each value depends on the
     *                      previous average.
     * @param   histories   Buffer in which the historical values of the
     *                      results will be kept.  This must be a rectangular
     *                      array, the first dimension being the same as
     *                      average.  The second dimension determines the
     *                      length of the history, and hence the time over
     *                      which values are averaged.  It is
     *                      important that this buffer is kept intact and
     *                      undisturbed between calls.
     * @param   index       Current history index.  The caller needs to pass
     *                      in zero initially, and save the return value
     *                      of this method to pass in as index next time.
     * @return              The updated index value.  Pass this in as
     *                      the index parameter next time around.
     * @throws  IllegalArgumentException    Invalid buffer size.
     */
    public final int getResults(float[] average, float[][] histories, int index) {
        if (average.length != blockSize / 2)
            throw new IllegalArgumentException("bad history buffer size in FFT:" +
                                               " must be " + (blockSize / 2) +
                                               "; given " + average.length);
        if (histories.length != blockSize / 2)
            throw new IllegalArgumentException("bad average buffer size in FFT:" +
                                               " must be " + (blockSize / 2) +
                                               "; given " + histories.length);
    
        // Update the index.
        int historyLen = histories[0].length;
        if (++index >= historyLen)
            index = 0;
       
        // Now do the rolling average of each value.
        for (int i = 0; i < blockSize/2; i++) {
            float r = (float) xre[i * 2];
            float im = i == 0 ? 0f : (float) xre[i * 2 - 1];
            final float val = (float) (Math.sqrt(r * r + im * im)) / blockSize;

            final float[] hist = histories[i];
            final float prev = hist[index];
            hist[index] = val;
            average[i] -= prev / historyLen;
            average[i] += val / historyLen;
        }
        
        return index;
    }


    // ******************************************************************** //
    // Results Analysis.
    // ******************************************************************** //

    /**
     * Given the results of an FFT, identify prominent frequencies
     * in the spectrum.
     * 
     * <p><b>Note:</b> this is experimental and not very good.
     * 
     * @param   spectrum    Audio spectrum data, as returned by
     *                      {@link #getResults(float[])}.
     * @param   results     Buffer into which the results will be placed.
     * @return              The parameter buffer.
     * @throws  IllegalArgumentException    Invalid buffer size.
     */
    public final int findKeyFrequencies(float[] spectrum, float[] results) {
        final int len = spectrum.length;
        
        // Find the average strength.
        float average = 0f;
        for (int i = 0; i < len; ++i) {
            average += spectrum[i];
        }
        average /= len;
        
        // Find all excursions above 2*average.  Group adjacent highs
        // together.
        int count = 0;
        for (int i = 0; i < len && count < results.length; ++i) {
            if (spectrum[i] > 2 * average) {
                // Compute the weighted average frequency of this peak.
                float tot = 0f;
                float wavg = 0f;
                int j;
                for (j = i; j < len && spectrum[j] > 3 * average; ++j) {
                    tot += spectrum[j];
                    wavg += spectrum[j] * (float) j;
                }
                wavg /= tot;
                results[count++] = wavg;
                
                // Skip past this peak.
                i = j;
            }
        }
 
        return count;
    }

    
    // ******************************************************************** //
    // Private Data.
    // ******************************************************************** //

    private RealDoubleFFT transformer;

    // The size of an input data block.
    private final int blockSize;
    
    // Working array -- real data being processed.
    private final double[] xre;

}

