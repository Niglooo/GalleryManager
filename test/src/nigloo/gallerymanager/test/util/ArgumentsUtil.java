package nigloo.gallerymanager.test.util;

import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.Arguments.ArgumentSet;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArgumentsUtil
{
    public static Stream<Arguments> allCombination(ArgumentSet... argumentValues)
    {
        if (argumentValues == null ||
            argumentValues.length == 0 ||
            Stream.of(argumentValues).anyMatch(a -> a.get() == null || a.get().length == 0)
        ) {
            return Stream.of();
        }

        int nbArgs = argumentValues.length;
        int nbCombinations = Stream.of(argumentValues)
                                   .mapToInt(a -> a.get().length)
                                   .reduce(1, (a, b) -> a * b);

        Object[][] values = new Object[nbCombinations][nbArgs];

        int nbRepSingle = 1;
        int nbRepAll = nbCombinations;
        for (int iArg = nbArgs - 1 ; iArg >= 0 ; iArg--)
        {
            Object[] argValues = argumentValues[iArg].get();
            int nbValuesArg = argValues.length;
            nbRepAll /= nbValuesArg;

            int iComb = 0;
            for (int iRepAll = 0 ; iRepAll < nbRepAll ; iRepAll++)
            {
                for (int iVal = 0; iVal < nbValuesArg; iVal++)
                {
                    for (int iRepSingle = 0; iRepSingle < nbRepSingle; iRepSingle++, iComb++)
                    {
                        assert iComb == (iRepAll * nbValuesArg * nbRepSingle + iVal * nbRepSingle + iRepSingle);
                        values[iComb][iArg] = argValues[iVal];
                    }
                }
            }

            nbRepSingle *= nbValuesArg;
        }

        String format = Stream
                .of(argumentValues)
                .map(a -> a.getName() + " = %s")
                .collect(Collectors.joining(" ; "));

        return Stream
                .of(values)
                .map(argValues -> Arguments.argumentSet(String.format(format, argValues), argValues));
    }

    public static ArgumentSet boolArg(String argName) {
        return Arguments.argumentSet(argName, true, false);
    }
}
